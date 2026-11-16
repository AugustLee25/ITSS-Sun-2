package app.hanare.help;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.points.RecordingNotifications;
import app.hanare.task.TaskAcceptedHook.AcceptedSubmission;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.HanareTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/** Nghiệm thu điểm spec 12.10: nhóm 5 người, chuyển/nhận task, duyệt hết, chưa đạt < 100%, duyệt lặp/đồng thời, giá khóa. */
@HanareTest
@Import(RecordingNotifications.Config.class)
class PointsAcceptanceTest extends HelpTestBase {

	private String potential(UserRef u, World w) throws Exception {
		return at(getOk(u, "/api/v1/groups/" + w.pid() + "/points/me"), "$.potentialPoints.percentLabel");
	}

	private String confirmed(UserRef u, World w) throws Exception {
		return at(getOk(u, "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel");
	}

	/** BE-B1 giả lập: người nộp {@code submitter} nộp trước giờ chốt, {@code reviewer} chấp nhận sau giờ chốt. */
	private void review(World w, String task, UserRef submitter, UserRef reviewer) {
		TaskRef t = w.t(task);
		UUID sub = fx.submission(w.pid(), new TaskRef(t.taskId(), t.roundId(), t.planId(), submitter.id(), t.cycleId(), t.deadline()),
				1, Instant.parse("2026-11-18T03:00:00Z"));
		acceptBySubmission(w, t.taskId(), t.roundId(), sub, reviewer, submitter, w.cycle().closesAt());
	}

	@Test
	void transferTakeOverApproveAllKeepsTotalAtOneHundredPercentAndUnfinishedWorkStaysBelow() throws Exception {
		World w = world();
		activate(w);
		for (UserRef u : List.of(w.a(), w.b(), w.c(), w.d(), w.lead())) {
			assertThat(potential(u, w)).isEqualTo("20,0000");
			assertThat(confirmed(u, w)).isEqualTo("0,0000");
		}
		// A chuyển task Khó 10 điểm % cho B → A 10%, B 30%
		MvcResult transfer = publishAndAccept(w, "a3", w.a(), w.b());
		assertThat(potential(w.a(), w)).isEqualTo("10,0000");
		assertThat(potential(w.b(), w)).isEqualTo("30,0000");
		// A nhận task 4 điểm % của C → A 14%, C 16%; D/L vẫn 20%
		MvcResult takeOver = publishAndAccept(w, "c1", w.c(), w.a());
		assertThat(potential(w.a(), w)).isEqualTo("14,0000");
		assertThat(potential(w.c(), w)).isEqualTo("16,0000");
		assertThat(potential(w.b(), w)).isEqualTo("30,0000");
		assertThat(potential(w.d(), w)).isEqualTo("20,0000");
		assertThat(potential(w.lead(), w)).isEqualTo("20,0000");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero(); // chỉ TL duyệt mới có ledger
		assertThat(confirmed(w.a(), w)).isEqualTo("0,0000");
		assertQuotaInvariant(w);

		// Helper nộp qua S10 (đúng hạn)
		UUID workB = uuid(transfer, "$.work.id");
		UUID workA = uuid(takeOver, "$.work.id");
		UUID subB = uuid(helperSubmit(w.b(), workB, 201), "$.submission.taskSubmissionId");
		UUID subA = uuid(helperSubmit(w.a(), workA, 201), "$.submission.taskSubmissionId");

		// duyệt mọi đầu ra TRỪ c5: tổng đã ghi nhận < 100% và không chuẩn hóa lại
		review(w, "a1", w.a(), w.lead());
		review(w, "a2", w.a(), w.lead());
		acceptBySubmission(w, w.t("a3").taskId(), w.t("a3").roundId(), subB, w.lead(), w.b(), w.cycle().closesAt());
		review(w, "b1", w.b(), w.lead());
		acceptBySubmission(w, w.t("c1").taskId(), w.t("c1").roundId(), subA, w.lead(), w.a(), w.cycle().closesAt());
		for (String t : List.of("c2", "c3", "c4")) {
			review(w, t, w.c(), w.lead());
		}
		review(w, "d1", w.d(), w.lead());
		review(w, "l1", w.lead(), w.d()); // task của TL: người duyệt độc lập (D) duyệt
		String groupSummary = "/api/v1/groups/" + w.pid() + "/points/summary";
		assertThat((Integer) at(getOk(w.b(), groupSummary), "$.confirmedPoints.pointUnits")).isEqualTo(960_000);
		assertThat((String) at(getOk(w.b(), groupSummary), "$.confirmedPoints.percentLabel")).isEqualTo("96,0000");
		assertThat((String) at(getOk(w.b(), groupSummary), "$.pendingPoints.percentLabel")).isEqualTo("4,0000");
		assertThat(confirmed(w.c(), w)).isEqualTo("12,0000");

		review(w, "c5", w.c(), w.lead());
		assertThat(confirmed(w.a(), w)).isEqualTo("14,0000"); // 3,3333 + 6,6667 + 4 (làm thay)
		assertThat(confirmed(w.b(), w)).isEqualTo("30,0000"); // 20 + 10 (làm thay)
		assertThat(confirmed(w.c(), w)).isEqualTo("16,0000");
		assertThat(confirmed(w.d(), w)).isEqualTo("20,0000");
		assertThat(confirmed(w.lead(), w)).isEqualTo("20,0000");
		assertThat((String) at(getOk(w.d(), groupSummary), "$.confirmedPoints.percentLabel")).isEqualTo("100,0000");
		assertThat((Integer) at(getOk(w.d(), groupSummary), "$.pendingPoints.pointUnits")).isZero();
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(11); // một khoản/task
		assertThat(count("SELECT COUNT(DISTINCT budget_id) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(11);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE project_id = :p1 AND status = 'CONFIRMED'", w.pid())).isEqualTo(2);
		assertQuotaInvariant(w);
	}

	@Test
	void concurrentAndRepeatedApprovalsNeverPayTheSameTaskTwice() throws Exception {
		World w = world();
		activate(w);
		TaskRef a1 = w.t("a1");
		UUID sub = fx.submission(w.pid(), a1, 1, Instant.parse("2026-11-18T03:00:00Z"));
		Instant at = w.cycle().closesAt().plusSeconds(3600);
		fx.review(w.pid(), sub, w.lead().id(), "ACCEPTED", at);
		AcceptedSubmission accepted = new AcceptedSubmission(w.pid(), a1.taskId(), a1.roundId(), sub, w.lead().id(), w.a().id(),
				at.minusSeconds(7200), at);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			futures.add(pool.submit(() -> hook.onSubmissionAccepted(accepted)));
		}
		for (Future<?> f : futures) {
			f.get();
		}
		pool.shutdown();
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM task_point_settlements WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(notes.ofType("POINTS_AWARDED")).hasSize(1);
	}

	@Test
	void publishedPriceNeverChangesWhenDifficultyChangesAndReviewIsBlockedWhileTheRequestHoldsTheFund() throws Exception {
		World w = world();
		activate(w);
		TaskRef a3 = w.t("a3");
		// TL đổi độ khó sau khóa (revision hiệu lực mới): giá đã khóa/đã công bố không đổi, khóa lại không định giá lại
		jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, base_revision_id, proposed_cycle_id, assignee_id,
				    title, description, expected_output, start_at, deadline, difficulty, status, created_by, submitted_by,
				    submitted_at, reviewed_by, reviewed_at, approval_source)
				SELECT project_id, round_id, 2, id, proposed_cycle_id, assignee_id, title, description, expected_output, start_at,
				       deadline, 'EASY', 'APPROVED', created_by, created_by, now(), created_by, now(), 'MEMBER_PLAN'
				  FROM task_plan_revisions WHERE id = :p
				""").param("p", a3.planId()).update();
		jdbc.sql("UPDATE task_review_rounds SET effective_plan_id = (SELECT id FROM task_plan_revisions WHERE round_id = :r AND revision_no = 2) WHERE id = :r")
				.param("r", a3.roundId()).update();
		assertThat((Integer) at(postIdem(w.lead(), groupPath(w, "/point-policy/activate"), "{\"confirmedWithMembers\":true}", 201),
				"$.fundedTaskCount")).isZero();
		assertThat(count("SELECT point_units FROM task_point_budgets WHERE task_id = :p1", a3.taskId())).isEqualTo(100_000);

		// quỹ đang được yêu cầu OPEN giữ: duyệt điểm bị chặn tới khi đóng yêu cầu (không giữ hai quyền nhận song song)
		UUID reqId = uuid(publish(w, "a3", w.a()), "$.id");
		UUID sub = fx.submission(w.pid(), a3, 1, Instant.parse("2026-11-18T03:00:00Z"));
		Instant at = w.cycle().closesAt().plusSeconds(3600);
		fx.review(w.pid(), sub, w.lead().id(), "ACCEPTED", at);
		AcceptedSubmission accepted = new AcceptedSubmission(w.pid(), a3.taskId(), a3.roundId(), sub, w.lead().id(), w.a().id(),
				at.minusSeconds(7200), at);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> hook.onSubmissionAccepted(accepted))
				.hasMessageContaining("đóng yêu cầu");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		postJson(w.lead(), "/api/v1/help-requests/" + reqId + "/cancel", "{\"version\":0}", 200);
		hook.onSubmissionAccepted(accepted);
		assertThat(count("SELECT delta_units FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(100_000);
		assertQuotaInvariant(w);
	}
}
