package app.hanare.help;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.points.RecordingNotifications;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.HanareTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Hồi quy cho các lỗi trong docs/BUG_REPORT.md (T-01…T-12). Mỗi test gắn mã lỗi trong tên. Các test đều bắt nguồn từ kịch bản
 * do agent Tester tái hiện được ở phiên bản trước khi sửa.
 */
@HanareTest
@Import(RecordingNotifications.Config.class)
class BugRegressionTest extends HelpTestBase {

	private String planOf(World w, UserRef viewer, String task) throws Exception {
		return at(getOk(viewer, "/api/v1/tasks/" + w.t(task).taskId()), "$.effectivePlan.id");
	}

	private String submitBody(String plan, String evidenceJson) {
		return "{\"planRevisionId\":\"" + plan + "\",\"resultNote\":\"Đã hoàn thiện toàn bộ phần việc theo kế hoạch\",\"evidence\":" + evidenceJson + "}";
	}

	private static final String OK_EVIDENCE = "[{\"type\":\"URL\",\"url\":\"https://example.test/ket-qua\",\"label\":\"Kết quả\"}]";

	/** Người gốc A đã được hoàn trả task sau khi Helper B báo không thể; trả về request id của yêu cầu ban đầu. */
	private UUID helperGivesUp(World w, String task, String resolution) throws Exception {
		MvcResult acc = publishAndAccept(w, task, w.a(), w.b());
		UUID work = uuid(acc, "$.work.id");
		String wp = "/api/v1/support-works/" + work;
		MvcResult un = postIdem(w.b(), wp + "/unable",
				"{\"reason\":\"Không kịp nữa\",\"version\":" + ((Number) at(acc, "$.work.version")).longValue() + "}", 200);
		MvcResult res = postIdem(w.lead(), wp + "/resolve-discussion",
				"{\"resolution\":\"" + resolution + "\",\"note\":\"Trả lại người gốc\",\"version\":" + ((Number) at(un, "$.version")).longValue() + "}", 200);
		return resolution.equals("REOPEN_REQUEST") ? uuid(res, "$.successorHelpRequestId") : null;
	}

	// ------------------------------------------------------------------------------------------------ T-01

	@Test
	void t01_originalMemberResultIsAcceptedAndPaidOnceAfterCancelRequest() throws Exception {
		World w = world();
		activate(w);
		TaskRef t = w.t("a2");
		helperGivesUp(w, "a2", "CANCEL_REQUEST");
		assertThat(currentRecipient(t.taskId())).isEqualTo(w.a().id());
		postIdem(w.a(), "/api/v1/task-rounds/" + t.roundId() + "/submissions", submitBody(planOf(w, w.a(), "a2"), OK_EVIDENCE), 201);
		clock.set(w.cycle().closesAt().plusSeconds(5));
		MvcResult review = postIdem(w.lead(), "/api/v1/task-submissions/" + latestSubmission(t.roundId()) + "/review", "{\"decision\":\"ACCEPTED\"}", 200);
		assertThat((String) at(review, "$.award.recipient.userId")).isEqualTo(w.a().id().toString());
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1 AND entry_type = 'AWARD'", w.pid())).isEqualTo(1);
		assertThat(count("SELECT COALESCE(SUM(delta_units),0) FROM point_ledger_entries WHERE recipient_id = :p1", w.a().id())).isEqualTo(66_667);
		assertThat(count("SELECT COALESCE(SUM(delta_units),0) FROM point_ledger_entries WHERE recipient_id = :p1", w.b().id())).isZero();
		assertQuotaInvariant(w);
	}

	@Test
	void t01_originalMemberResultIsAcceptedAfterReopenThenTheSuccessorRequestIsCancelled() throws Exception {
		World w = world();
		activate(w);
		TaskRef t = w.t("a2");
		UUID successor = helperGivesUp(w, "a2", "REOPEN_REQUEST");
		long version = ((Number) at(getOk(w.lead(), "/api/v1/help-requests/" + successor), "$.version")).longValue();
		postJson(w.lead(), "/api/v1/help-requests/" + successor + "/cancel", "{\"version\":" + version + "}", 200);
		postIdem(w.a(), "/api/v1/task-rounds/" + t.roundId() + "/submissions", submitBody(planOf(w, w.a(), "a2"), OK_EVIDENCE), 201);
		clock.set(w.cycle().closesAt().plusSeconds(5));
		postIdem(w.lead(), "/api/v1/task-submissions/" + latestSubmission(t.roundId()) + "/review", "{\"decision\":\"ACCEPTED\"}", 200);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1 AND entry_type = 'AWARD'", w.pid())).isEqualTo(1);
		assertQuotaInvariant(w);
	}

	// ------------------------------------------------------------------------------------------------ T-02

	@Test
	void t02_blankOrTooShortAfterTrimIsRejectedEverywhereTextIsRequired() throws Exception {
		World w = world();
		activate(w);
		String path = groupPath(w, "/help-requests");
		String task = w.t("a3").taskId().toString();
		String ben = w.a().id().toString();
		String head = "{\"taskId\":\"" + task + "\",\"beneficiaryId\":\"" + ben + "\",";
		String tail = ",\"privacyChecked\":true}";
		for (String body : List.of(head + "\"title\":\"   \",\"description\":\"Mô tả đầy đủ cho phần việc\"" + tail,
				head + "\"title\":\"Tiêu đề hợp lệ\",\"description\":\"          \"" + tail,
				head + "\"title\":\" ab \",\"description\":\"Mô tả đầy đủ cho phần việc\"" + tail)) {
			assertThat(errorCode(postIdem(w.lead(), path, body, 400))).isEqualTo("VALIDATION_FAILED");
		}
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE project_id = :p1", w.pid())).isZero();

		MvcResult pub = publish(w, "a1", w.a());
		String patch = "{\"description\":\"            \",\"version\":" + version(pub) + "}";
		assertThat(errorCode(patchJson(w.lead(), "/api/v1/help-requests/" + uuid(pub, "$.id"), patch, 400))).isEqualTo("VALIDATION_FAILED");

		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		String unable = "{\"reason\":\"          \",\"version\":" + ((Number) at(acc, "$.work.version")).longValue() + "}";
		assertThat(errorCode(postIdem(w.b(), "/api/v1/support-works/" + uuid(acc, "$.work.id") + "/unable", unable, 400))).isEqualTo("VALIDATION_FAILED");

		String blankSupport = "{\"obstacleType\":\"PERSONAL\",\"description\":\"          \",\"neededSupport\":\"Cần giúp\"}";
		assertThat(errorCode(postJson(w.c(), "/api/v1/tasks/" + w.t("c1").taskId() + "/support-requests", blankSupport, 400))).isEqualTo("VALIDATION_FAILED");
	}

	// ------------------------------------------------------------------------------------------------ T-05 / T-07

	@SuppressWarnings("unchecked")
	private static <T> T boardField(MvcResult board, UUID taskId, String path) throws Exception {
		List<Object> found = JsonPath.read(body(board), "$.tasks[?(@.task.id=='" + taskId + "')]." + path);
		assertThat(found).hasSize(1);
		return (T) found.get(0);
	}

	@Test
	void t05_overdueStartsStrictlyAfterTheDeadlineSoItAgreesWithTimeliness() throws Exception {
		World w = world();
		TaskRef t = w.t("b1");
		String board = groupPath(w, "/dashboard/members/" + w.b().id() + "/tasks");
		clock.set(t.deadline());
		MvcResult at = getOk(w.lead(), board);
		assertThat((Boolean) boardField(at, t.taskId(), "overdue")).isFalse();
		assertThat((String) boardField(at, t.taskId(), "task.timeliness")).isEqualTo("NOT_DUE");
		clock.set(t.deadline().plus(Duration.ofMillis(1)));
		MvcResult after = getOk(w.lead(), board);
		assertThat((Boolean) boardField(after, t.taskId(), "overdue")).isTrue();
		assertThat((String) boardField(after, t.taskId(), "task.timeliness")).isEqualTo("OVERDUE_NO_RESULT");
	}

	@Test
	void t07_overdueTaskAfterCutoffExplainsThatCarryoverComesFirst() throws Exception {
		World w = world();
		clock.set(w.cycle().closesAt().plusSeconds(60));
		MvcResult board = getOk(w.lead(), groupPath(w, "/dashboard/members/" + w.a().id() + "/tasks"));
		assertThat((Boolean) boardField(board, w.t("a2").taskId(), "canPublishToWorkspace")).isFalse();
		assertThat((String) boardField(board, w.t("a2").taskId(), "publishBlockedCode")).isEqualTo("CARRYOVER_REQUIRED");
		assertThat(errorCode(postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a2", w.a(), null), 409))).isEqualTo("CARRYOVER_REQUIRED");
	}

	// ------------------------------------------------------------------------------------------------ T-08

	@Test
	void t08_taskListRejectsBadPagingAndBadAssigneeLikeEveryOtherList() throws Exception {
		World w = world();
		for (String q : List.of("size=0", "size=101", "size=-1", "page=-1", "size=abc", "page=x", "assigneeId=xyz")) {
			assertThat(errorCode(getStatus(w.a(), groupPath(w, "/tasks?" + q), 400))).as(q).isEqualTo("VALIDATION_FAILED");
		}
		assertThat((Integer) at(getOk(w.a(), groupPath(w, "/tasks?size=100&page=0")), "$.size")).isEqualTo(100);
		assertThat((Integer) at(getOk(w.a(), groupPath(w, "/tasks")), "$.size")).isEqualTo(20);
	}

	// ------------------------------------------------------------------------------------------------ T-09

	@Test
	void t09_evidenceRejectsCredentialsInternalHostsAndDuplicatesButAcceptsNormalLinks() throws Exception {
		World w = world();
		TaskRef t = w.t("a2");
		String path = "/api/v1/task-rounds/" + t.roundId() + "/submissions";
		String plan = planOf(w, w.a(), "a2");
		for (String bad : List.of("https://user:pw@example.test/x", "https://localhost/x", "https://127.0.0.1/x", "https://192.168.1.5/x",
				"https://10.0.0.1/x", "https://[::1]/x")) {
			String ev = "[{\"type\":\"URL\",\"url\":\"" + bad + "\"}]";
			assertThat(errorCode(postIdem(w.a(), path, submitBody(plan, ev), 400))).as(bad).isEqualTo("VALIDATION_FAILED");
		}
		String dup = "[{\"type\":\"URL\",\"url\":\"https://example.test/a\"},{\"type\":\"URL\",\"url\":\"HTTPS://EXAMPLE.test/a\"}]";
		assertThat(errorCode(postIdem(w.a(), path, submitBody(plan, dup), 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(count("SELECT COUNT(*) FROM task_submissions WHERE round_id = :p1", t.roundId())).isZero();
		postIdem(w.a(), path, submitBody(plan, "[{\"type\":\"URL\",\"url\":\"https://example.test/a\"},{\"type\":\"URL\",\"url\":\"https://example.test/b\"}]"), 201);
	}

	@Test
	void t09_numbersAndBooleansAreNotCoercedAndWeakCredentialsAreRefused() throws Exception {
		World w = world();
		activate(w);
		String path = groupPath(w, "/help-requests");
		String base = "{\"taskId\":\"" + w.t("a3").taskId() + "\",\"beneficiaryId\":\"" + w.a().id() + "\",\"title\":\"Tiêu đề hợp lệ\",\"description\":\"Mô tả đầy đủ cho phần việc\",";
		postIdem(w.lead(), path, base + "\"estimatedEffortMinutes\":1.5,\"privacyChecked\":true}", 400);
		postIdem(w.lead(), path, base + "\"privacyChecked\":\"true\"}", 400);
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE project_id = :p1", w.pid())).isZero();
		postIdem(w.lead(), path, base + "\"estimatedEffortMinutes\":90,\"privacyChecked\":true}", 201);

		for (String body : List.of("{\"name\":\"Hà\",\"email\":\"a@b\",\"password\":\"matkhau-1234\"}",
				"{\"name\":\"Hà\",\"email\":\"ha@example.com\",\"password\":\"        \"}")) {
			postJson(null, "/api/v1/auth/register", body, 400);
		}
		postJson(null, "/api/v1/auth/register", "{\"name\":\"Hà\",\"email\":\"ha@example.com\",\"password\":\"matkhau-1234\"}", 201);
	}

	// ------------------------------------------------------------------------------------------------ T-10 / T-12

	@Test
	void t10_oneOpenPrivateRequestPerSenderAndTaskUntilTheLeadClosesIt() throws Exception {
		World w = world();
		String path = "/api/v1/tasks/" + w.t("a3").taskId() + "/support-requests";
		String body = "{\"obstacleType\":\"TIME\",\"description\":\"Trùng lịch thi nên không kịp\",\"neededSupport\":\"Cần người hỗ trợ\"}";
		MvcResult first = postJson(w.a(), path, body, 201);
		assertThat(errorCode(postJson(w.a(), path, body, 409))).isEqualTo("SUPPORT_REQUEST_ALREADY_OPEN");
		assertThat(notes.ofType("NEED_HELP")).hasSize(1);
		postJson(w.lead(), "/api/v1/support-requests/" + uuid(first, "$.id") + "/decision",
				"{\"action\":\"RESOLVED_PRIVATELY\",\"response\":\"Đã hướng dẫn riêng\",\"version\":0}", 200);
		postJson(w.a(), path, body, 201);
	}

	@Test
	void t12_leadCannotAcceptAnOfferFromSomeoneWhoLeftTheGroup() throws Exception {
		World w = world();
		activate(w);
		MvcResult pub = publish(w, "a3", w.a());
		UUID req = uuid(pub, "$.id");
		UUID offerId = uuid(offer(w.b(), req, version(pub), 201), "$.id");
		jdbc.sql("UPDATE project_members SET left_at = now() WHERE project_id = :p AND user_id = :u").param("p", w.pid()).param("u", w.b().id()).update();
		assertThat(errorCode(accept(w, req, offerId, version(pub), 409))).isEqualTo("OFFER_HELPER_NOT_ACTIVE");
		assertThat(effectiveAssignee(w.t("a3").roundId())).isEqualTo(w.a().id());
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM support_works WHERE project_id = :p1", w.pid())).isZero();
		assertQuotaInvariant(w);
	}
}
