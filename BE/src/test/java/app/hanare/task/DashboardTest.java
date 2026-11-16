package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.common.DbTime;
import app.hanare.testsupport.DbFixtures.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Mục D: dashboard S03, màu khung tên XANH/VÀNG/ĐỎ/XÁM (spec 0.6, 4.2), GroupHealth, khu TL, chi tiết thành viên. */
class DashboardTest extends B1Base {

	private String dash(World w) {
		return "/api/v1/groups/" + w.pid() + "/dashboard";
	}

	private String color(MvcResult r, UserRef u, String field) throws Exception {
		List<String> l = at(r, "$.memberProgress[?(@.member.userId=='" + u.id() + "')].evaluation." + field);
		assertThat(l).hasSize(1);
		return l.getFirst();
	}

	/** A: 3 task đến hạn, không nộp (đỏ). B: 2 task, 1 nộp đúng hạn + 1 không (vàng). C: 1 task nộp đúng hạn (xanh tạm tính). D: task chưa đến hạn (xám). */
	private void scenario(World w) throws Exception {
		Instant d1 = Instant.parse("2026-11-17T13:00:00Z");
		Instant d2 = Instant.parse("2026-11-18T13:00:00Z");
		Instant d3 = Instant.parse("2026-11-19T13:00:00Z");
		approvedTask(w, w.a(), "Task A1", "EASY", d1);
		approvedTask(w, w.a(), "Task A2", "EASY", d2);
		approvedTask(w, w.a(), "Task A3", "EASY", d3);
		Task b1 = approvedTask(w, w.b(), "Task B1", "EASY", d2);
		approvedTask(w, w.b(), "Task B2", "EASY", d3);
		Task c1 = approvedTask(w, w.c(), "Task C1", "EASY", d1);
		approvedTask(w, w.d(), "Task D1", "EASY", Instant.parse("2026-11-20T13:00:00Z"));
		clock.set(Instant.parse("2026-11-17T10:00:00Z"));
		submitResult(c1, w.c(), 201);
		clock.set(Instant.parse("2026-11-18T10:00:00Z"));
		submitResult(b1, w.b(), 201);
		clock.set(Instant.parse("2026-11-19T14:00:00Z"));
	}

	@Test
	void memberColorsFollowSpecAndMissingReportDoesNotChangeColor() throws Exception {
		World w = world();
		scenario(w);
		MvcResult r = GET(dash(w), w.b(), 200);
		assertThat(color(r, w.a(), "color")).isEqualTo("RED");
		assertThat(color(r, w.a(), "reasonCode")).isEqualTo("LATE_3_PLUS");
		assertThat(color(r, w.a(), "statusLabel")).isEqualTo("Trễ 3 task");
		assertThat(color(r, w.a(), "colorLabel")).isEqualTo("Đỏ");
		assertThat(color(r, w.b(), "color")).isEqualTo("YELLOW");
		assertThat(color(r, w.b(), "reasonCode")).isEqualTo("LATE_1_2");
		assertThat(color(r, w.c(), "color")).isEqualTo("GREEN");
		assertThat(color(r, w.c(), "reasonCode")).isEqualTo("ALL_SUBMITTED_PENDING_REVIEW");
		assertThat(color(r, w.c(), "statusLabel")).isEqualTo("Đã nộp đủ — chờ TL duyệt");
		List<Boolean> prov = at(r, "$.memberProgress[?(@.member.userId=='" + w.c().id() + "')].evaluation.provisional");
		assertThat(prov).containsExactly(true);
		assertThat(color(r, w.d(), "color")).isEqualTo("GRAY"); // chưa có task đến hạn
		assertThat(color(r, w.d(), "reasonCode")).isEqualTo("NO_TASK_DUE");
		assertThat(color(r, w.lead(), "color")).isEqualTo("GRAY");
		// thiếu báo cáo (NOT_RESPONDED) không đổi màu: C chưa báo cáo vẫn xanh; reportState hiện riêng
		List<String> rs = at(r, "$.memberProgress[?(@.member.userId=='" + w.c().id() + "')].reportState");
		assertThat(rs).containsExactly("NOT_RESPONDED");
		List<Integer> late = at(r, "$.memberProgress[?(@.member.userId=='" + w.a().id() + "')].evaluation.counts.lateTasks");
		assertThat(late).containsExactly(3);
		// TL xác nhận nguy cơ bỏ việc -> đỏ kèm người xác nhận; D chưa có task đến hạn nhưng vẫn đỏ vì TL xác nhận
		jdbc.sql("""
				INSERT INTO risk_reports (project_id, subject_member_id, reported_by, risk_type, public_summary, status, confirmed_by, confirmed_at)
				VALUES (:p, :d, :l, 'DROPOUT_RISK', 'TL xác nhận nguy cơ', 'CONFIRMED', :l, :at)
				""").param("p", w.pid()).param("d", w.d().id()).param("l", w.lead().id()).param("at", DbTime.toDb(clock.instant())).update();
		MvcResult r2 = GET(dash(w), w.b(), 200);
		assertThat(color(r2, w.d(), "color")).isEqualTo("RED");
		assertThat(color(r2, w.d(), "reasonCode")).isEqualTo("DROPOUT_RISK_CONFIRMED");
		List<String> by = at(r2, "$.memberProgress[?(@.member.userId=='" + w.d().id() + "')].dropoutRisk.confirmedBy.name");
		assertThat(by).containsExactly(w.lead().name());
		assertThat((String) at(r2, "$.group.health")).isEqualTo("NEEDS_ATTENTION"); // risk đang mở
		assertThat((List<?>) at(r2, "$.sharedRisks")).hasSize(1);
	}

	@Test
	void greenBecomesConfirmedAfterLeadAcceptsAndCutoffEvaluationIsFrozen() throws Exception {
		World w = world();
		scenario(w);
		closeCycle(w, w.c1());
		MvcResult before = GET(dash(w) + "?cycleId=" + w.c1().id(), w.b(), 200);
		assertThat((String) at(before, "$.cycle.phase")).isEqualTo("AWAITING_LEAD_REVIEW");
		assertThat(color(before, w.c(), "reasonCode")).isEqualTo("ALL_SUBMITTED_PENDING_REVIEW");
		// sau cutoff, đủ 3 task của A đều "không có kết quả"; D có 1 task đến hạn lúc 20/11 13:00Z và không nộp -> trễ 1 (vàng)
		assertThat(color(before, w.d(), "color")).isEqualTo("YELLOW");
		UUID subC = jdbc.sql("SELECT s.id FROM task_submissions s JOIN task_review_rounds r ON r.id = s.round_id JOIN task_plan_revisions p ON p.id = r.effective_plan_id WHERE p.title = 'Task C1'")
				.query(UUID.class).single();
		clock.advance(java.time.Duration.ofHours(1));
		POST("/api/v1/task-submissions/" + subC + "/review", w.lead(), body("decision", "ACCEPTED"), 200);
		MvcResult after = GET(dash(w) + "?cycleId=" + w.c1().id(), w.b(), 200);
		assertThat(color(after, w.c(), "reasonCode")).isEqualTo("ALL_ACCEPTED");
		assertThat(color(after, w.c(), "statusLabel")).isEqualTo("Đã xác nhận hoàn thành");
		List<Boolean> prov = at(after, "$.memberProgress[?(@.member.userId=='" + w.c().id() + "')].evaluation.provisional");
		assertThat(prov).containsExactly(false);
		// ảnh chụp tại giờ chốt vẫn là "chờ duyệt"
		List<String> cut = at(after, "$.memberProgress[?(@.member.userId=='" + w.c().id() + "')].cutoffEvaluation.reasonCode");
		assertThat(cut).containsExactly("ALL_SUBMITTED_PENDING_REVIEW");
	}

	@Test
	void dashboardStructureLeadPanelAndAccessRules() throws Exception {
		World w = world();
		createTask(w, w.a(), "Chưa lập kế hoạch", "EASY", DL);
		Task pending = approvedTask(w, w.b(), "Đã duyệt", "MEDIUM", DL);
		MvcResult p = createTask(w, w.c(), "Chờ duyệt", "HARD", DL);
		POST("/api/v1/plans/" + at(p, "$.pendingRevision.id") + "/submit", w.c(), body("version", longAt(p, "$.pendingRevision.version")), 200);
		jdbc.sql("""
				INSERT INTO risk_reports (project_id, task_id, subject_member_id, reported_by, risk_type, public_summary)
				VALUES (:p, :t, :b, :b, 'DELAY_RISK', 'Phần API có thể cần thêm 1 ngày')
				""").param("p", w.pid()).param("t", pending.taskId()).param("b", w.b().id()).update();
		// Member
		MvcResult m = GET(dash(w), w.a(), 200);
		assertThat((String) at(m, "$.myRole")).isEqualTo("MEMBER");
		assertThat((Object) at(m, "$.leadPanel")).isNull();
		assertThat((Integer) at(m, "$.group.memberCount")).isEqualTo(5);
		assertThat((String) at(m, "$.group.leader.name")).isEqualTo(w.lead().name());
		assertThat((String) at(m, "$.cycle.id")).isEqualTo(w.c1().id().toString());
		assertThat((String) at(m, "$.nextCycleId")).isEqualTo(w.c2().id().toString());
		assertThat((Object) at(m, "$.previousCycleId")).isNull();
		assertThat((Integer) at(m, "$.memberProgress.length()")).isEqualTo(5);
		assertThat((String) at(m, "$.memberProgress[0].role")).isEqualTo("LEAD"); // TL đứng đầu
		List<String> kinds = at(m, "$.myActions[*].kind");
		assertThat(kinds).contains("COMPLETE_PLAN");
		assertThat((Boolean) at(m, "$.flags.iHaveAnyTask")).isTrue();
		assertThat((Boolean) at(m, "$.flags.groupHasAnyTask")).isTrue();
		assertThat((Boolean) at(m, "$.myPoints.policyActive")).isFalse();
		assertThat((Object) at(m, "$.myPoints.confirmedPoints")).isNull();
		assertThat((Integer) at(m, "$.myTasks.length()")).isEqualTo(1);
		assertThat((Integer) at(m, "$.nextCheckpoint.myRequiredTaskCount")).isEqualTo(0); // chưa có kế hoạch duyệt
		assertThat((String) at(m, "$.sharedRisks[0].publicSummary")).isEqualTo("Phần API có thể cần thêm 1 ngày");
		assertThat((String) at(m, "$.sharedRisks[0].taskTitle")).isEqualTo("Đã duyệt");
		assertThat((Integer) at(m, "$.recentActivity.length()")).isGreaterThan(0);
		assertThat((Integer) at(m, "$.recentActivity.length()")).isLessThanOrEqualTo(5);
		// TL
		MvcResult l = GET(dash(w), w.lead(), 200);
		assertThat((Integer) at(l, "$.leadPanel.pendingPlans.count")).isEqualTo(1);
		assertThat((String) at(l, "$.leadPanel.pendingPlans.items[0].taskTitle")).isEqualTo("Chờ duyệt");
		assertThat((Boolean) at(l, "$.leadPanel.pendingPlans.items[0].isChange")).isFalse();
		assertThat((Integer) at(l, "$.leadPanel.privateSupportRequests.count")).isEqualTo(0);
		List<String> lk = at(l, "$.myActions[*].kind");
		assertThat(lk).contains("REVIEW_PLAN");
		// quyền / lỗi
		GET(dash(w), fx.user("Người ngoài"), 404);
		GET(dash(w) + "?cycleId=" + UUID.randomUUID(), w.a(), 404);
		var other = fx.groupOf5();
		UUID otherSched = fx.schedule(other.projectId(), other.leader().id());
		var otherCycles = fx.weeklyCycles(other.projectId(), otherSched, 1);
		GET(dash(w) + "?cycleId=" + otherCycles.getFirst().id(), w.a(), 404);
		// chi tiết thành viên
		MvcResult dt = GET(dash(w) + "/members/" + w.b().id(), w.a(), 200);
		assertThat((String) at(dt, "$.progress.member.name")).isEqualTo(w.b().name());
		assertThat((Integer) at(dt, "$.tasks.length()")).isEqualTo(1);
		assertThat((String) at(dt, "$.tasks[0].title")).isEqualTo("Đã duyệt");
		assertThat((String) at(dt, "$.tasks[0].timeliness")).isEqualTo("NOT_DUE");
		GET(dash(w) + "/members/" + UUID.randomUUID(), w.a(), 404);
		GET(dash(w) + "/members/" + w.b().id(), fx.user("Người ngoài khác"), 404);
	}

	@Test
	void healthIsInsufficientWhenNoTasksOrMissingUpdatesAfterReminder() throws Exception {
		World w = world();
		assertThat((String) at(GET(dash(w), w.a(), 200), "$.group.health")).isEqualTo("INSUFFICIENT_INFO");
		assertThat((Boolean) at(GET(dash(w), w.a(), 200), "$.flags.groupHasAnyTask")).isFalse();
		approvedTask(w, w.a(), "Có task", "EASY", DL);
		assertThat((String) at(GET(dash(w), w.a(), 200), "$.group.health")).isEqualTo("ON_TRACK");
		// qua giờ nhắc (cutoff − 7h) mà A có task bắt buộc chưa báo cáo -> thiếu thông tin (không đồng nghĩa trễ)
		clock.set(w.c1().closesAt().minusSeconds(3600));
		MvcResult r = GET(dash(w), w.a(), 200);
		assertThat((String) at(r, "$.group.health")).isEqualTo("INSUFFICIENT_INFO");
		assertThat((String) at(r, "$.group.healthLabel")).isEqualTo("Chưa đủ thông tin");
		assertThat((Boolean) at(r, "$.nextCheckpoint.canReportNow")).isTrue();
		List<String> kinds = at(r, "$.myActions[*].kind");
		assertThat(kinds).contains("CHECKPOINT_REPORT");
	}
}
