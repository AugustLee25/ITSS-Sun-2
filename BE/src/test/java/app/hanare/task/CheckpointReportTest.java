package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Mục H: báo cáo checkpoint hàng loạt điền sẵn (my-report, PUT nháp, submit), khung mở/đóng, thiếu báo cáo ≠ chưa nộp. */
class CheckpointReportTest extends B1Base {

	private String url(World w) {
		return "/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/my-report";
	}

	private String item(Task t, String declared, String risk) {
		return "{\"taskId\":\"" + t.taskId() + "\",\"declaredResult\":\"" + declared + "\",\"riskLevel\":\"" + risk + "\"}";
	}

	@Test
	void prefilledReportDraftIncompleteSubmitAndLock() throws Exception {
		World w = world();
		Task t1 = approvedTask(w, w.a(), "Task 1", "EASY", Instant.parse("2026-11-17T13:00:00Z"));
		Task t2 = approvedTask(w, w.a(), "Task 2", "MEDIUM", Instant.parse("2026-11-18T13:00:00Z"));
		Task t3 = approvedTask(w, w.a(), "Task 3", "HARD", Instant.parse("2026-11-19T13:00:00Z"));
		Task other = approvedTask(w, w.b(), "Của B", "EASY", Instant.parse("2026-11-19T13:00:00Z"));
		submitResult(t1, w.a(), 201);
		// điền sẵn: task đã nộp thật -> RESULT_SUBMITTED + ON_TRACK; chưa tính là phản hồi
		MvcResult g = GET(url(w), w.a(), 200);
		assertThat((String) at(g, "$.state")).isEqualTo("NOT_RESPONDED");
		assertThat((Integer) at(g, "$.items.length()")).isEqualTo(3);
		assertThat((String) at(g, "$.items[0].title")).isEqualTo("Task 1");
		assertThat((String) at(g, "$.items[0].declaredResult")).isEqualTo("RESULT_SUBMITTED");
		assertThat((Boolean) at(g, "$.items[0].prefilled")).isTrue();
		assertThat((String) at(g, "$.items[0].riskLevel")).isEqualTo("ON_TRACK");
		assertThat((String) at(g, "$.items[0].submission.evidence[0].url")).isEqualTo("https://github.com/x/y/pull/7");
		assertThat((String) at(g, "$.items[0].timeliness")).isEqualTo("ON_TIME_PENDING_REVIEW");
		assertThat((String) at(g, "$.items[1].declaredResult")).isEqualTo("RESULT_MISSING");
		assertThat((Integer) at(g, "$.completeness.requiredCount")).isEqualTo(3);
		assertThat((Integer) at(g, "$.completeness.respondedCount")).isEqualTo(0);
		assertThat((Boolean) at(g, "$.completeness.isComplete")).isFalse();
		assertThat((Boolean) at(g, "$.canEdit")).isTrue();
		assertThat((Object) at(g, "$.version")).isNull();
		assertThat((Integer) at(GET(url(w), w.c(), 200), "$.items.length()")).isEqualTo(0); // kỳ không có task bắt buộc
		// lưu nháp 2/3 task
		MvcResult d = PUT(url(w), w.a(), "{\"version\":null,\"items\":[" + item(t1, "RESULT_SUBMITTED", "ON_TRACK") + ","
				+ "{\"taskId\":\"" + t2.taskId() + "\",\"declaredResult\":\"RESULT_MISSING\",\"riskLevel\":\"NEEDS_LEAD_HELP\",\"note\":\"Kẹt dữ liệu\","
				+ "\"expectedCompletionAt\":\"2026-11-21T13:00:00Z\"}]}", 200);
		assertThat((String) at(d, "$.state")).isEqualTo("DRAFT");
		assertThat((String) at(d, "$.status")).isEqualTo("DRAFT");
		assertThat((Integer) at(d, "$.completeness.respondedCount")).isEqualTo(2);
		assertThat((String) at(d, "$.items[1].riskLevel")).isEqualTo("NEEDS_LEAD_HELP");
		assertThat((String) at(d, "$.items[1].note")).isEqualTo("Kẹt dữ liệu");
		assertThat((String) at(d, "$.items[1].expectedCompletionAt")).isEqualTo("2026-11-21T13:00:00Z");
		assertThat((Boolean) at(d, "$.items[2].prefilled")).isTrue();
		long v = longAt(d, "$.version");
		// lỗi: sai version, khai sai so với lần nộp thật, task ngoài phạm vi
		assertThat(code(PUT(url(w), w.a(), "{\"version\":" + (v + 5) + ",\"items\":[]}", 409))).isEqualTo("VERSION_CONFLICT");
		assertThat(code(PUT(url(w), w.a(), "{\"version\":" + v + ",\"items\":[" + item(t2, "RESULT_SUBMITTED", "ON_TRACK") + "]}", 422)))
				.isEqualTo("RESULT_DECLARATION_MISMATCH");
		assertThat(code(PUT(url(w), w.a(), "{\"version\":" + v + ",\"items\":[" + item(t1, "RESULT_MISSING", "ON_TRACK") + "]}", 422)))
				.isEqualTo("RESULT_DECLARATION_MISMATCH");
		PUT(url(w), w.a(), "{\"version\":" + v + ",\"items\":[" + item(other, "RESULT_MISSING", "ON_TRACK") + "]}", 400);
		// gửi khi thiếu: 422 nhưng nháp vẫn được giữ
		MvcResult inc = POST(url(w) + "/submit", w.a(), "{\"version\":" + v + "}", 422);
		assertThat(code(inc)).isEqualTo("REPORT_INCOMPLETE");
		assertThat((String) at(inc, "$.fieldErrors[0].field")).isEqualTo("items[" + t3.taskId() + "]");
		MvcResult kept = GET(url(w), w.a(), 200);
		assertThat((String) at(kept, "$.state")).isEqualTo("DRAFT");
		assertThat((Integer) at(kept, "$.completeness.respondedCount")).isEqualTo(2);
		// xác nhận hàng loạt: gửi cả ba item điền sẵn
		String all = item(t1, "RESULT_SUBMITTED", "ON_TRACK") + "," + item(t2, "RESULT_MISSING", "AT_RISK") + "," + item(t3, "RESULT_MISSING", "ON_TRACK");
		clock.set(Instant.parse("2026-11-19T10:00:00Z"));
		MvcResult s = POST(url(w) + "/submit", w.a(), "{\"version\":" + longAt(kept, "$.version") + ",\"items\":[" + all + "]}", 200);
		assertThat((String) at(s, "$.state")).isEqualTo("SUBMITTED");
		assertThat((String) at(s, "$.submittedAt")).isEqualTo("2026-11-19T10:00:00Z"); // server cấp
		assertThat((String) at(s, "$.submittedLabel")).isEqualTo("Đã gửi đầy đủ");
		assertThat((Boolean) at(s, "$.completeness.isComplete")).isTrue();
		assertThat((Integer) at(s, "$.revisionNo")).isEqualTo(1);
		// gửi lặp (không items, không nháp) -> không đổi
		MvcResult again = POST(url(w) + "/submit", w.a(), "{\"version\":" + longAt(s, "$.version") + "}", 200);
		assertThat((Integer) at(again, "$.revisionNo")).isEqualTo(1);
		assertThat((String) at(again, "$.state")).isEqualTo("SUBMITTED");
		// item đã gửi bất biến trong DB
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_reports r JOIN checkpoint_participants p ON p.id = r.participant_id WHERE p.cycle_id = :c AND p.member_id = :m")
				.param("c", w.c1().id()).param("m", w.a().id()).query(Long.class).single()).isEqualTo(1L);
		// sửa tiếp trước cutoff -> revision mới (DRAFT), bản cũ vẫn SUBMITTED
		MvcResult rev2 = PUT(url(w), w.a(), "{\"version\":" + longAt(s, "$.version") + ",\"items\":[" + all + "]}", 200);
		assertThat((Integer) at(rev2, "$.revisionNo")).isEqualTo(2);
		assertThat((String) at(rev2, "$.status")).isEqualTo("DRAFT");
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_reports r JOIN checkpoint_participants p ON p.id = r.participant_id WHERE p.cycle_id = :c AND p.member_id = :m AND r.status = 'SUBMITTED'")
				.param("c", w.c1().id()).param("m", w.a().id()).query(Long.class).single()).isEqualTo(1L);
		// sau cutoff: khóa
		clock.set(w.c1().closesAt().plusSeconds(1));
		assertThat(code(PUT(url(w), w.a(), "{\"version\":" + longAt(rev2, "$.version") + ",\"items\":[]}", 409))).isEqualTo("CYCLE_CLOSED");
		assertThat(code(POST(url(w) + "/submit", w.a(), "{\"version\":" + longAt(rev2, "$.version") + "}", 409))).isEqualTo("CYCLE_CLOSED");
		MvcResult locked = GET(url(w), w.a(), 200);
		assertThat((Boolean) at(locked, "$.canEdit")).isFalse();
		assertThat((String) at(locked, "$.lockReason.code")).isEqualTo("CYCLE_CLOSED");
		// người ngoài nhóm
		GET(url(w), fx.user("Người ngoài"), 404);
	}

	@Test
	void windowNotOpenAndBacklogOfPreviousCycle() throws Exception {
		World w = world();
		// kỳ 2 có khung mở hẹp: chỉ mở trong 2 giờ cuối
		jdbc.sql("UPDATE checkpoint_cycles SET opens_at = :o WHERE id = :c").param("o", app.hanare.common.DbTime.toDb(w.c2().closesAt().minusSeconds(7200)))
				.param("c", w.c2().id()).update();
		String url2 = "/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c2().id() + "/my-report";
		MvcResult g = GET(url2, w.a(), 200);
		assertThat((Boolean) at(g, "$.canEdit")).isFalse();
		assertThat((String) at(g, "$.lockReason.code")).isEqualTo("REPORT_WINDOW_NOT_OPEN");
		assertThat(code(PUT(url2, w.a(), "{\"version\":null,\"items\":[]}", 409))).isEqualTo("REPORT_WINDOW_NOT_OPEN");
		// task của kỳ 1 bị yêu cầu sửa, chưa chốt chuyển kỳ -> backlog (không bắt buộc) ở kỳ 2
		Task t = approvedTask(w, w.a(), "Tồn đọng", "EASY", Instant.parse("2026-11-20T13:00:00Z"));
		Task waiting = approvedTask(w, w.a(), "Chờ duyệt kỳ trước", "EASY", Instant.parse("2026-11-20T13:00:00Z"));
		clock.set(Instant.parse("2026-11-20T12:30:00Z"));
		submitResult(waiting, w.a(), 201);
		closeCycle(w, w.c1());
		MvcResult b = GET(url2, w.a(), 200);
		assertThat((Integer) at(b, "$.items.length()")).isEqualTo(0);
		assertThat((Integer) at(b, "$.backlog.length()")).isEqualTo(2);
		List<String> states = at(b, "$.backlog[*].state");
		assertThat(states).containsExactlyInAnyOrder("CARRYOVER_REQUIRED", "AWAITING_REVIEW_PREVIOUS_CYCLE");
		List<String> msgs = at(b, "$.backlog[?(@.title=='Tồn đọng')].message");
		assertThat(msgs).containsExactly("Cần TL chốt lịch xử lý tiếp");
		assertThat(t.taskId()).isNotEqualTo(UUID.randomUUID());
	}
}
