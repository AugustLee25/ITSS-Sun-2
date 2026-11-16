package app.hanare.points;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.task.TaskAcceptedHook;
import app.hanare.task.TaskAcceptedHook.AcceptedSubmission;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.HanareTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/** K: points/me, me/tasks, me/ledger, ledger/{id}, simulation-candidates, simulate, summary, reverse + ghi điểm khi duyệt. */
@HanareTest
@Import(RecordingNotifications.Config.class)
class PointQueryTest extends ApiTestBase {

	@Autowired
	TaskAcceptedHook hook;

	private String g(World w, String suffix) {
		return "/api/v1/groups/" + w.pid() + suffix;
	}

	/** Giả lập BE-B1: ghi lần nộp + quyết định ACCEPTED rồi gọi cổng TaskAcceptedHook trong cùng bước. */
	private UUID acceptAsLead(World w, TaskRef task, UserRef submitter, UserRef reviewer) {
		Instant submittedAt = Instant.parse("2026-11-18T03:00:00Z");
		int no = (int) count("SELECT COALESCE(MAX(submission_no), 0) + 1 FROM task_submissions WHERE round_id = :p1", task.roundId());
		UUID sub = fx.submission(w.pid(), new TaskRef(task.taskId(), task.roundId(), task.planId(), submitter.id(), task.cycleId(),
				task.deadline()), no, submittedAt);
		Instant reviewedAt = w.cycle().closesAt().plusSeconds(3600);
		clock.set(reviewedAt);
		fx.review(w.pid(), sub, reviewer.id(), "ACCEPTED", reviewedAt);
		hook.onSubmissionAccepted(new AcceptedSubmission(w.pid(), task.taskId(), task.roundId(), sub, reviewer.id(),
				submitter.id(), submittedAt, reviewedAt));
		return sub;
	}

	@Test
	void meBeforeAndAfterActivationDistinguishesInitialConfirmedPendingAndPotential() throws Exception {
		World w = world();
		MvcResult before = getOk(w.a(), g(w, "/points/me"));
		assertThat((Boolean) at(before, "$.policyActive")).isFalse();
		assertThat((String) at(before, "$.statusLabel")).isEqualTo("Chưa có phương án điểm đã duyệt");
		assertThat((Object) at(before, "$.initialPoints")).isNull();
		assertThat((Integer) at(before, "$.confirmedPoints.pointUnits")).isZero();

		activate(w);
		MvcResult me = getOk(w.a(), g(w, "/points/me"));
		assertThat((Boolean) at(me, "$.policyActive")).isTrue();
		assertThat((Integer) at(me, "$.memberCount")).isEqualTo(5);
		assertThat((String) at(me, "$.initialPoints.percentLabel")).isEqualTo("20,0000");
		assertThat((String) at(me, "$.confirmedPoints.percentLabel")).isEqualTo("0,0000"); // 0% đã nhận
		assertThat((String) at(me, "$.pendingPoints.percentLabel")).isEqualTo("20,0000");
		assertThat((String) at(me, "$.potentialPoints.percentLabel")).isEqualTo("20,0000");
		assertThat((Integer) at(me, "$.transferredOut.taskCount")).isZero();
		assertThat((Integer) at(me, "$.takenOver.taskCount")).isZero();
		assertThat((Integer) at(me, "$.pendingTransfer.openRequestCount")).isZero();
		assertThat((String) at(me, "$.goalCompletionPercentLabel")).isEqualTo("0,0000");
		getStatus(fx.user("Người ngoài"), g(w, "/points/me"), 404);
	}

	@Test
	void myTasksListFiltersAndRejectsBadEnums() throws Exception {
		World w = world();
		activate(w);
		MvcResult all = getOk(w.a(), g(w, "/points/me/tasks"));
		assertThat((Integer) at(all, "$.totalItems")).isEqualTo(3);
		List<String> titles = JsonPath.read(body(all), "$.items[*].difficulty");
		assertThat(titles).containsExactlyInAnyOrder("EASY", "MEDIUM", "HARD");
		assertThat((String) at(all, "$.items[0].source")).isEqualTo("OWN");
		assertThat((String) at(all, "$.items[0].currentRecipient.name")).isEqualTo(w.a().name());
		assertThat((Integer) at(getOk(w.a(), g(w, "/points/me/tasks?status=NOT_STARTED")), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.a(), g(w, "/points/me/tasks?status=IN_PROGRESS&size=2")), "$.items.length()")).isEqualTo(2);
		assertThat((Integer) at(getOk(w.a(), g(w, "/points/me/tasks?source=TAKEN_OVER")), "$.totalItems")).isZero();
		assertThat(errorCode(getStatus(w.a(), g(w, "/points/me/tasks?status=NOPE"), 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(getStatus(w.a(), g(w, "/points/me/tasks?size=1000"), 400))).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void simulationCandidatesAndSimulateAreReadOnlyAndValidated() throws Exception {
		World w = world();
		assertThat(errorCode(getStatus(w.a(), g(w, "/points/me/simulation-candidates"), 409))).isEqualTo("POINT_POLICY_NOT_ACTIVE");
		assertThat(errorCode(postJson(w.a(), g(w, "/points/me/simulate"),
				"{\"transferOutTaskIds\":[\"" + w.t("a3").taskId() + "\"]}", 409))).isEqualTo("POINT_POLICY_NOT_ACTIVE");
		activate(w);
		MvcResult cand = getOk(w.a(), g(w, "/points/me/simulation-candidates"));
		assertThat((Integer) at(cand, "$.transferable.length()")).isEqualTo(3);
		assertThat((Integer) at(cand, "$.takeable.length()")).isZero();

		String a3 = w.t("a3").taskId().toString();
		MvcResult sim = postJson(w.a(), g(w, "/points/me/simulate"), "{\"transferOutTaskIds\":[\"" + a3 + "\"]}", 200);
		assertThat((String) at(sim, "$.startPotentialPoints.percentLabel")).isEqualTo("20,0000");
		assertThat((String) at(sim, "$.endPotentialPoints.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(sim, "$.steps[0].kind")).isEqualTo("START");
		assertThat((String) at(sim, "$.steps[1].kind")).isEqualTo("TRANSFER_OUT");
		assertThat((String) at(sim, "$.steps[1].delta.percentLabel")).isEqualTo("-10,0000");
		assertThat((String) at(sim, "$.steps[1].label")).isEqualTo("Chuyển task 10,0000 điểm % → còn 10,0000%");
		assertThat((String) at(sim, "$.notice")).isEqualTo("Đây là mô phỏng, không ghi điểm");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat(count("SELECT COUNT(*) FROM task_point_assignments WHERE status = 'SUPERSEDED' AND project_id = :p1", w.pid())).isZero();

		// task của người khác / chưa có yêu cầu mở / trùng lặp -> 422
		String c1 = w.t("c1").taskId().toString();
		MvcResult bad = postJson(w.a(), g(w, "/points/me/simulate"),
				"{\"transferOutTaskIds\":[\"" + c1 + "\",\"" + a3 + "\",\"" + a3 + "\"]}", 422);
		assertThat(errorCode(bad)).isEqualTo("SIMULATION_INVALID_TASK");
		List<String> fields = JsonPath.read(body(bad), "$.fieldErrors[*].field");
		assertThat(fields).containsExactly("transferOutTaskIds[0]", "transferOutTaskIds[2]");
		assertThat(errorCode(postJson(w.a(), g(w, "/points/me/simulate"), "{}", 400))).isEqualTo("VALIDATION_FAILED");
	}

	@Test
	void acceptedResultWritesExactlyOneAwardAndRepeatedApprovalDoesNotPayTwice() throws Exception {
		World w = world();
		activate(w);
		UUID sub = acceptAsLead(w, w.t("a1"), w.a(), w.lead());
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(count("SELECT delta_units FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(33_333);
		assertThat(count("SELECT COUNT(*) FROM task_point_settlements WHERE revoked_at IS NULL AND project_id = :p1", w.pid())).isEqualTo(1);
		// duyệt lặp (S05 rồi S11 / double click): gọi lại cổng không cấp thêm
		hook.onSubmissionAccepted(new AcceptedSubmission(w.pid(), w.t("a1").taskId(), w.t("a1").roundId(), sub, w.lead().id(),
				w.a().id(), Instant.parse("2026-11-18T03:00:00Z"), clock.instant()));
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(notes.ofType("POINTS_AWARDED")).hasSize(1);
		assertThat(notes.ofType("POINTS_AWARDED").get(0).recipientIds()).containsExactly(w.a().id());
		assertThat(notes.ofType("POINTS_AWARDED").get(0).message()).contains("+3,3333");

		MvcResult me = getOk(w.a(), g(w, "/points/me"));
		assertThat((String) at(me, "$.confirmedPoints.percentLabel")).isEqualTo("3,3333");
		assertThat((String) at(me, "$.pendingPoints.percentLabel")).isEqualTo("16,6667");
		assertThat((String) at(me, "$.potentialPoints.percentLabel")).isEqualTo("20,0000");
		assertThat((String) at(me, "$.goalCompletionPercentLabel")).isEqualTo("16,6665");
		assertThat((String) at(getOk(w.a(), g(w, "/points/me/tasks?status=AWARDED")), "$.items[0].statusLabel")).isEqualTo("Đã ghi điểm");

		MvcResult summary = getOk(w.b(), g(w, "/points/summary"));
		assertThat((Integer) at(summary, "$.confirmedPoints.pointUnits")).isEqualTo(33_333);
		assertThat((Integer) at(summary, "$.unallocatedPoints.pointUnits")).isZero();
		assertThat((Integer) at(summary, "$.pendingPoints.pointUnits")).isEqualTo(1_000_000 - 33_333);
		assertThat((Integer) at(summary, "$.totalPoints.pointUnits")).isEqualTo(1_000_000);
	}

	@Test
	void ledgerHistoryDetailAndAccessRules() throws Exception {
		World w = world();
		activate(w);
		acceptAsLead(w, w.t("a1"), w.a(), w.lead());
		MvcResult ledger = getOk(w.a(), g(w, "/points/me/ledger"));
		assertThat((Integer) at(ledger, "$.totalItems")).isEqualTo(1);
		assertThat((String) at(ledger, "$.items[0].kind")).isEqualTo("AWARD");
		assertThat((String) at(ledger, "$.items[0].group")).isEqualTo("POINTS_RECORDED");
		assertThat((String) at(ledger, "$.items[0].delta.percentLabel")).isEqualTo("+3,3333");
		assertThat((String) at(ledger, "$.items[0].runningTotal.percentLabel")).isEqualTo("3,3333");
		assertThat((String) at(ledger, "$.items[0].source")).isEqualTo("OWN");
		assertThat((String) at(ledger, "$.items[0].label")).startsWith("Đã ghi nhận +3,3333 điểm % từ task");
		assertThat((Integer) at(getOk(w.a(), g(w, "/points/me/ledger?group=ASSIGNMENT")), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), g(w, "/points/me/ledger")), "$.totalItems")).isZero(); // sổ cá nhân
		assertThat(errorCode(getStatus(w.a(), g(w, "/points/me/ledger?group=XYZ"), 400))).isEqualTo("VALIDATION_FAILED");

		String entryId = at(ledger, "$.items[0].ledgerEntryId");
		MvcResult detail = getOk(w.a(), g(w, "/points/ledger/" + entryId));
		assertThat((String) at(detail, "$.entryType")).isEqualTo("AWARD");
		assertThat((String) at(detail, "$.budgetPoints.percentLabel")).isEqualTo("3,3333");
		assertThat((String) at(detail, "$.submission.id")).isNotBlank();
		assertThat((String) at(detail, "$.review.decision")).isEqualTo("ACCEPTED");
		assertThat((String) at(detail, "$.settlement.approvedBy.name")).isEqualTo(w.lead().name());
		assertThat((Object) at(detail, "$.reversesEntryId")).isNull();
		getOk(w.lead(), g(w, "/points/ledger/" + entryId));
		assertThat(errorCode(getStatus(w.b(), g(w, "/points/ledger/" + entryId), 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(getStatus(w.a(), g(w, "/points/ledger/" + UUID.randomUUID()), 404))).isEqualTo("NOT_FOUND");
	}

	@Test
	void reverseAddsReversalOnceAndCanReissueWithoutTouchingOldLedgerRows() throws Exception {
		World w = world();
		activate(w);
		acceptAsLead(w, w.t("a2"), w.a(), w.lead());
		UUID settlement = jdbc.sql("SELECT id FROM task_point_settlements WHERE revoked_at IS NULL AND project_id = :p").param("p", w.pid()).query(UUID.class).single();
		String url = "/api/v1/point-settlements/" + settlement + "/reverse";
		String json = "{\"reason\":\"Nhập nhầm người nhận, hiệu chỉnh lại\"}";

		assertThat(errorCode(postIdem(w.b(), url, json, 403))).isEqualTo("NOT_AUTHORIZED_REVIEWER");
		assertThat(errorCode(postIdem(w.a(), url, json, 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		assertThat(errorCode(postJson(w.lead(), url, json, 400))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat(errorCode(postIdem(w.lead(), url, "{\"reason\":\"ngắn\"}", 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postIdem(fx.user("Ngoài"), url, json, 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(postIdem(w.lead(), "/api/v1/point-settlements/" + UUID.randomUUID() + "/reverse", json, 404)))
				.isEqualTo("NOT_FOUND");

		MvcResult r = postIdem(w.lead(), url, "{\"reason\":\"Nhập nhầm người nhận, hiệu chỉnh lại\",\"reissueRecipientUserId\":\""
				+ w.a().id() + "\"}", 200);
		assertThat((String) at(r, "$.reversalEntryId")).isNotBlank();
		assertThat((String) at(r, "$.newLedgerEntryId")).isNotBlank();
		assertThat((String) at(r, "$.task.id")).isEqualTo(w.t("a2").taskId().toString());
		// sổ cái chỉ thêm dòng: AWARD, REVERSAL, AWARD; ròng = P_t, mỗi khoản đảo đúng một lần
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(3);
		assertThat(count("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(66_667);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE entry_type = 'REVERSAL' AND project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(notes.ofType("POINTS_ADJUSTED")).hasSize(1);
		assertThat(errorCode(postIdem(w.lead(), url, json, 409))).isEqualTo("SETTLEMENT_ALREADY_REVOKED");

		MvcResult history = getOk(w.a(), g(w, "/points/me/ledger"));
		List<String> kinds = JsonPath.read(body(history), "$.items[*].kind");
		assertThat(kinds).containsExactlyInAnyOrder("AWARD", "REVERSAL", "AWARD");
		assertThat((String) at(getOk(w.a(), g(w, "/points/me")), "$.confirmedPoints.percentLabel")).isEqualTo("6,6667");

		// đảo không kèm cấp lại: điểm ròng về 0, task vẫn thuộc người nhận hiện tại
		UUID second = jdbc.sql("SELECT id FROM task_point_settlements WHERE revoked_at IS NULL AND project_id = :p").param("p", w.pid()).query(UUID.class).single();
		MvcResult r2 = postIdem(w.lead(), "/api/v1/point-settlements/" + second + "/reverse", json, 200);
		assertThat((Object) at(r2, "$.newSettlementId")).isNull();
		assertThat(count("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat((String) at(getOk(w.a(), g(w, "/points/me")), "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(4);
		// cấp lại cho người không phải người thực hiện hợp lệ của bản đã duyệt bị từ chối
		acceptAsLead(w, w.t("a1"), w.a(), w.lead());
		UUID third = jdbc.sql("SELECT id FROM task_point_settlements WHERE revoked_at IS NULL AND project_id = :p").param("p", w.pid()).query(UUID.class).single();
		MvcResult invalid = postIdem(w.lead(), "/api/v1/point-settlements/" + third + "/reverse",
				"{\"reason\":\"Cấp lại cho người khác để thử\",\"reissueRecipientUserId\":\"" + w.b().id() + "\"}", 400);
		assertThat(errorCode(invalid)).isEqualTo("VALIDATION_FAILED");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(5);
	}
}
