package app.hanare.help;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.points.ApiTestBase;
import app.hanare.task.TaskAcceptedHook;
import app.hanare.task.TaskAcceptedHook.AcceptedSubmission;
import app.hanare.testsupport.DbFixtures.UserRef;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Hàm dùng chung cho test luồng hỗ trợ: đăng yêu cầu, đề nghị, chọn Helper, nộp, và giả lập BE-B1 duyệt kết quả. */
abstract class HelpTestBase extends ApiTestBase {

	@Autowired
	protected TaskAcceptedHook hook;

	protected String groupPath(World w, String suffix) {
		return "/api/v1/groups/" + w.pid() + suffix;
	}

	protected String publishJson(World w, String task, UserRef beneficiary, UUID supportRequestId) {
		return "{\"taskId\":\"" + w.t(task).taskId() + "\",\"beneficiaryId\":\"" + beneficiary.id()
				+ "\",\"title\":\"Hỗ trợ toàn bộ task " + task + "\",\"description\":\"Hoàn thiện toàn bộ phần việc theo kế hoạch đã duyệt\","
				+ "\"estimatedEffortMinutes\":240,\"skillNotes\":\"Spring Boot\",\"privacyChecked\":true"
				+ (supportRequestId == null ? "" : ",\"supportRequestId\":\"" + supportRequestId + "\"") + "}";
	}

	protected MvcResult publish(World w, String task, UserRef beneficiary) throws Exception {
		return postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, task, beneficiary, null), 201);
	}

	protected long version(MvcResult r) throws Exception {
		return ((Number) at(r, "$.version")).longValue();
	}

	protected MvcResult offer(UserRef helper, UUID requestId, long requestVersion, int status) throws Exception {
		return postJson(helper, "/api/v1/help-requests/" + requestId + "/offers",
				"{\"message\":\"Mình rảnh tối nay\",\"acknowledgedScope\":true,\"helpRequestVersion\":" + requestVersion + "}", status);
	}

	protected MvcResult accept(World w, UUID requestId, UUID offerId, long requestVersion, int status) throws Exception {
		return postIdem(w.lead(), "/api/v1/help-requests/" + requestId + "/accept-offer",
				"{\"offerId\":\"" + offerId + "\",\"version\":" + requestVersion + "}", status);
	}

	/** Đăng + một Helper đề nghị + TL chấp thuận; trả response accept-offer. */
	protected MvcResult publishAndAccept(World w, String task, UserRef beneficiary, UserRef helper) throws Exception {
		MvcResult pub = publish(w, task, beneficiary);
		UUID reqId = uuid(pub, "$.id");
		MvcResult off = offer(helper, reqId, version(pub), 201);
		return accept(w, reqId, uuid(off, "$.id"), version(pub), 200);
	}

	protected static String evidenceJson() {
		return "{\"resultNote\":\"Đã hoàn thiện toàn bộ phần việc, link kết quả bên dưới\","
				+ "\"evidence\":[{\"type\":\"URL\",\"url\":\"https://example.test/ket-qua\",\"label\":\"Kết quả\"}],\"actualMinutes\":180}";
	}

	protected MvcResult helperSubmit(UserRef helper, UUID workId, int status) throws Exception {
		return postIdem(helper, "/api/v1/support-works/" + workId + "/submissions", evidenceJson(), status);
	}

	/** Giả lập BE-B1: ghi quyết định ACCEPTED của TL (sau giờ chốt) rồi gọi cổng TaskAcceptedHook. */
	protected void acceptBySubmission(World w, UUID taskId, UUID roundId, UUID submissionId, UserRef reviewer, UserRef submitter,
			Instant cutoff) {
		Instant reviewedAt = cutoff.plusSeconds(3600);
		clock.set(reviewedAt);
		if (count("SELECT COUNT(*) FROM task_result_reviews WHERE submission_id = :p1", submissionId) == 0) {
			fx.review(w.pid(), submissionId, reviewer.id(), "ACCEPTED", reviewedAt);
		}
		hook.onSubmissionAccepted(new AcceptedSubmission(w.pid(), taskId, roundId, submissionId, reviewer.id(), submitter.id(),
				reviewedAt.minusSeconds(7200), reviewedAt));
	}

	protected UUID latestSubmission(UUID roundId) {
		return jdbc.sql("SELECT id FROM task_submissions WHERE round_id = :r ORDER BY submission_no DESC LIMIT 1")
				.param("r", roundId).query(UUID.class).single();
	}

	protected String reservationState(UUID taskId) {
		return jdbc.sql("""
				SELECT r.state FROM task_point_reservations r JOIN task_point_budgets b ON b.id = r.budget_id
				 WHERE b.task_id = :t ORDER BY r.created_at DESC, r.id LIMIT 1
				""").param("t", taskId).query(String.class).optional().orElse(null);
	}

	protected UUID currentRecipient(UUID taskId) {
		return jdbc.sql("""
				SELECT a.recipient_id FROM task_point_assignments a JOIN task_point_budgets b ON b.id = a.budget_id
				 WHERE b.task_id = :t AND a.status = 'CURRENT'
				""").param("t", taskId).query(UUID.class).single();
	}

	protected UUID effectiveAssignee(UUID roundId) {
		return jdbc.sql("""
				SELECT p.assignee_id FROM task_review_rounds r JOIN task_plan_revisions p ON p.id = r.effective_plan_id WHERE r.id = :r
				""").param("r", roundId).query(UUID.class).single();
	}

	/** Bất biến quỹ (12.8.7): Σ initial = 1.000.000; Σ funded + Σ dự phòng = 1.000.000; ledger ròng mỗi task ∈ [0, P_t]. */
	protected void assertQuotaInvariant(World w) {
		assertThat(count("SELECT COALESCE(SUM(initial_units), 0) FROM member_point_budgets WHERE project_id = :p1", w.pid()))
				.isEqualTo(1_000_000);
		assertThat(count("""
				SELECT (SELECT COALESCE(SUM(point_units), 0) FROM task_point_budgets WHERE project_id = :p1 AND state = 'FUNDED')
				     + (SELECT COALESCE(SUM(reserve_units), 0) FROM member_point_budgets WHERE project_id = :p1)
				""", w.pid())).isEqualTo(1_000_000);
		assertThat(count("""
				SELECT COUNT(*) FROM task_point_budgets b WHERE b.project_id = :p1 AND (
				  (SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries l WHERE l.budget_id = b.id) NOT BETWEEN 0 AND b.point_units)
				""", w.pid())).isZero();
		assertThat(count("""
				SELECT COUNT(*) FROM task_point_budgets b WHERE b.project_id = :p1
				   AND (SELECT COUNT(*) FROM task_point_assignments a WHERE a.budget_id = b.id AND a.status = 'CURRENT') <> 1
				""", w.pid())).isZero();
		assertThat(count("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p1", w.pid()))
				.isBetween(0L, 1_000_000L);
	}
}
