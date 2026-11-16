package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** CutoffHook: snapshot bất biến, LeadCycleSummary, đúng MỘT CHECKPOINT_REVIEW_REQUIRED, lead-summary, finalize. */
class CutoffTest extends B1Base {

	private String reportUrl(World w, UUID cycleId) {
		return "/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + cycleId + "/my-report";
	}

	@Test
	void cutoffFreezesSnapshotsNotifiesLeadOnceAndSummarizes() throws Exception {
		World w = world();
		Instant dl1 = Instant.parse("2026-11-19T13:00:00Z");
		Task t1 = approvedTask(w, w.a(), "A đã nộp", "EASY", dl1);
		Task t2 = approvedTask(w, w.a(), "A chưa nộp", "MEDIUM", dl1);
		Task t3 = approvedTask(w, w.b(), "B đã nộp", "HARD", dl1);
		clock.set(Instant.parse("2026-11-19T10:00:00Z"));
		submitResult(t1, w.a(), 201);
		submitResult(t3, w.b(), 201);
		// 4/5 báo cáo (A không phản hồi); B báo đủ, những người không có task gửi báo cáo rỗng
		POST(reportUrl(w, w.c1().id()) + "/submit", w.b(), "{\"version\":null,\"items\":null}", 200);
		for (var u : List.of(w.lead(), w.c(), w.d())) {
			POST(reportUrl(w, w.c1().id()) + "/submit", u, "{\"version\":null,\"items\":[]}", 200);
		}
		closeCycle(w, w.c1());
		// snapshot đã đóng băng, đủ 3 task
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_task_snapshots WHERE cycle_id = :c AND frozen_at IS NOT NULL")
				.param("c", w.c1().id()).query(Long.class).single()).isEqualTo(3L);
		assertThat(jdbc.sql("SELECT execution_state_at_cutoff FROM checkpoint_task_snapshots WHERE round_id = :r").param("r", t2.roundId())
				.query(String.class).single()).isEqualTo("TODO");
		assertThat(jdbc.sql("SELECT submission_id_at_cutoff IS NOT NULL FROM checkpoint_task_snapshots WHERE round_id = :r").param("r", t1.roundId())
				.query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_participants WHERE cycle_id = :c AND frozen_at IS NOT NULL AND role_at_cutoff IS NOT NULL")
				.param("c", w.c1().id()).query(Long.class).single()).isEqualTo(5L);
		assertThat(jdbc.sql("SELECT required_task_count FROM checkpoint_participants WHERE cycle_id = :c AND member_id = :m")
				.param("c", w.c1().id()).param("m", w.a().id()).query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT report_id_at_cutoff IS NOT NULL FROM checkpoint_participants WHERE cycle_id = :c AND member_id = :m")
				.param("c", w.c1().id()).param("m", w.b().id()).query(Boolean.class).single()).isTrue();
		// bất biến: không sửa được snapshot đã đóng băng
		assertThatThrownBy(() -> jdbc.sql("UPDATE checkpoint_task_snapshots SET title_snapshot = 'Sửa lén' WHERE round_id = :r")
				.param("r", t1.roundId()).update()).isInstanceOf(Exception.class);
		// vòng: task chưa nộp -> CARRYOVER_REQUIRED, đã nộp -> AWAITING_REVIEW
		assertThat(jdbc.sql("SELECT state FROM task_review_rounds WHERE id = :r").param("r", t2.roundId()).query(String.class).single()).isEqualTo("CARRYOVER_REQUIRED");
		assertThat(jdbc.sql("SELECT state FROM task_review_rounds WHERE id = :r").param("r", t1.roundId()).query(String.class).single()).isEqualTo("AWAITING_REVIEW");
		// đúng MỘT thông báo cho TL, kèm tóm tắt
		assertThat(notes.of("CHECKPOINT_REVIEW_REQUIRED")).hasSize(1);
		var ev = notes.of("CHECKPOINT_REVIEW_REQUIRED").getFirst();
		assertThat(ev.recipientIds()).containsExactly(w.lead().id());
		assertThat(ev.dedupKey()).isEqualTo("checkpoint:" + w.c1().id() + ":review-required");
		assertThat(ev.entityId()).isEqualTo(w.c1().id());
		assertThat(ev.message()).contains("4/5").contains("2 task chờ bạn duyệt").contains("1 task chưa có kết quả");
		// job chạy lại: không tạo bản ghi/thông báo thứ hai
		lifecycle.onCycleClosed(new app.hanare.schedule.CycleService.CycleRef(w.c1().id(), w.pid(), 1, null, w.c1().periodStart(),
				w.c1().periodStart(), w.c1().closesAt().minusSeconds(25200), w.c1().closesAt(), "CLOSED", clock.instant(), null));
		assertThat(notes.of("CHECKPOINT_REVIEW_REQUIRED")).hasSize(1);
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_task_snapshots WHERE cycle_id = :c").param("c", w.c1().id()).query(Long.class).single()).isEqualTo(3L);
		// lead-summary (chỉ TL)
		String url = "/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/lead-summary";
		GET(url, w.a(), 403);
		MvcResult cur = GET(url, w.lead(), 200);
		assertThat((Integer) at(cur, "$.members.total")).isEqualTo(5);
		assertThat((Integer) at(cur, "$.members.reported")).isEqualTo(4);
		assertThat((Integer) at(cur, "$.members.notResponded")).isEqualTo(1);
		assertThat((String) at(cur, "$.notRespondedMembers[0].name")).isEqualTo(w.a().name());
		assertThat((Integer) at(cur, "$.tasks.total")).isEqualTo(3);
		assertThat((Integer) at(cur, "$.tasks.submittedPendingReview")).isEqualTo(2);
		assertThat((Integer) at(cur, "$.tasks.noResult")).isEqualTo(1);
		assertThat((Integer) at(cur, "$.tasks.late")).isEqualTo(1);
		assertThat((Integer) at(cur, "$.tasks.carryoverRequired")).isEqualTo(1);
		assertThat((String) at(cur, "$.phase")).isEqualTo("AWAITING_LEAD_REVIEW");
		MvcResult cut = GET(url + "?view=AT_CUTOFF", w.lead(), 200);
		assertThat((Boolean) at(cut, "$.isAtCutoff")).isTrue();
		assertThat((Integer) at(cut, "$.tasks.accepted")).isEqualTo(0);
		// TL duyệt sau chốt: CURRENT đổi, AT_CUTOFF bất biến
		UUID sub1 = jdbc.sql("SELECT id FROM task_submissions WHERE round_id = :r").param("r", t1.roundId()).query(UUID.class).single();
		clock.advance(java.time.Duration.ofHours(2));
		POST("/api/v1/task-submissions/" + sub1 + "/review", w.lead(), body("decision", "ACCEPTED"), 200);
		assertThat((Integer) at(GET(url, w.lead(), 200), "$.tasks.accepted")).isEqualTo(1);
		MvcResult cut2 = GET(url + "?view=AT_CUTOFF", w.lead(), 200);
		assertThat((Integer) at(cut2, "$.tasks.accepted")).isEqualTo(0);
		assertThat((String) at(cut2, "$.asOf")).isEqualTo((String) at(cut, "$.asOf"));
		// kỳ chưa chốt: không có bản chụp
		GET("/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c2().id() + "/lead-summary?view=AT_CUTOFF", w.lead(), 409);
		GET(url + "?view=XYZ", w.lead(), 400);
		// chưa FINALIZED vì còn task chờ duyệt (B) và task cần chuyển kỳ (A chưa nộp)
		assertThat(jdbc.sql("SELECT status FROM checkpoint_cycles WHERE id = :c").param("c", w.c1().id()).query(String.class).single()).isEqualTo("CLOSED");
		assertThat(lifecycle.finalizeIfDone(w.c1().id())).isFalse();
	}

	@Test
	void reviewRequiredIsSentEvenWhenEveryoneReportedAndEmptyCycleFinalizes() throws Exception {
		World w = world();
		for (var u : w.g().all()) {
			POST(reportUrl(w, w.c1().id()) + "/submit", u, "{\"version\":null,\"items\":[]}", 200);
		}
		closeCycle(w, w.c1());
		assertThat(notes.of("CHECKPOINT_REVIEW_REQUIRED")).hasSize(1);
		MvcResult s = GET("/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/lead-summary?view=AT_CUTOFF", w.lead(), 200);
		assertThat((Integer) at(s, "$.members.reported")).isEqualTo(5);
		assertThat((Integer) at(s, "$.members.notResponded")).isEqualTo(0);
		assertThat(jdbc.sql("SELECT status FROM checkpoint_cycles WHERE id = :c").param("c", w.c1().id()).query(String.class).single()).isEqualTo("FINALIZED");
	}
}
