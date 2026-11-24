package app.hanare.help;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.common.DbTime;
import app.hanare.points.RecordingNotifications;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.HanareTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Nghiệm thu Solution 2 (HANARE_SOLUTION2_CORE_REQUIREMENTS.md). Tên mỗi test mang tag yêu cầu {@code S2_<Nhóm><Số>} để
 * {@code harness/run-harness.ps1} truy vết yêu cầu → test. Các quy tắc đã có test ở nơi khác được harness trỏ thẳng tới đó.
 */
@HanareTest
@Import(RecordingNotifications.Config.class)
class Solution2AcceptanceTest extends HelpTestBase {

	private static final String SECRET = "Hoàn cảnh gia đình riêng của A";

	private static String supportJson(String description) {
		return "{\"obstacleType\":\"PERSONAL\",\"description\":\"" + description
				+ "\",\"neededSupport\":\"Cần người làm thay phần này\"}";
	}

	private MvcResult privateRequest(World w, String task) throws Exception {
		return postJson(w.a(), "/api/v1/tasks/" + w.t(task).taskId() + "/support-requests",
				supportJson(SECRET + " nên khó làm kịp"), 201);
	}

	/** Người dùng thực hiện → id bản kế hoạch hiệu lực hiện tại của task (qua API chi tiết task). */
	private String currentPlanId(World w, app.hanare.testsupport.DbFixtures.UserRef viewer, String task) throws Exception {
		return at(getOk(viewer, "/api/v1/tasks/" + w.t(task).taskId()), "$.effectivePlan.id");
	}

	private String popupBody(String planId) {
		return "{\"planRevisionId\":\"" + planId + "\",\"resultNote\":\"Đã hoàn thiện toàn bộ phần việc theo kế hoạch\","
				+ "\"evidence\":[{\"type\":\"URL\",\"url\":\"https://example.test/ket-qua\",\"label\":\"Kết quả\"}]}";
	}

	// ------------------------------------------------------------------------------------------------ A — điểm vào & riêng tư

	@Test
	void s2_A2_memberHelpRequestIsPrivateToTheLeadAndChangesNothing() throws Exception {
		World w = world();
		activate(w);
		MvcResult sr = privateRequest(w, "a3");
		UUID srId = uuid(sr, "$.id");

		assertThat(errorCode(getStatus(w.b(), "/api/v1/support-requests/" + srId, 403))).isEqualTo("FORBIDDEN");
		assertThat((Integer) at(getOk(w.b(), groupPath(w, "/support-requests?scope=ALL")), "$.totalItems")).isZero();
		getOk(w.lead(), "/api/v1/support-requests/" + srId);
		getOk(w.a(), "/api/v1/support-requests/" + srId);

		// bấm "Cần giúp đỡ" không tự chuyển task, đổi deadline hay làm mất điểm
		UUID round = w.t("a3").roundId();
		assertThat(effectiveAssignee(round)).isEqualTo(w.a().id());
		assertThat((String) at(getOk(w.a(), "/api/v1/tasks/" + w.t("a3").taskId()), "$.deadline"))
				.isEqualTo(w.t("a3").deadline().toString());
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE project_id = :p1", w.pid())).isZero();
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.a().id());
		assertThat((String) at(getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.potentialPoints.percentLabel"))
				.isEqualTo("20,0000");
		// chỉ TL được báo, và thông báo không mang nội dung riêng
		assertThat(notes.ofType("NEED_HELP")).hasSize(1);
		assertThat(notes.ofType("NEED_HELP").get(0).recipientIds()).containsExactly(w.lead().id());
		assertThat(notes.allText()).doesNotContain(SECRET);
	}

	@Test
	void s2_A3_privateTextNeverLeaksIntoWorkspaceDashboardNotificationsOrRiskApi() throws Exception {
		World w = world();
		activate(w);
		UUID srId = uuid(privateRequest(w, "a3"), "$.id");
		MvcResult pub = postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a3", w.a(), srId), 201);
		UUID reqId = uuid(pub, "$.id");

		String[] publicReads = { groupPath(w, "/help-requests"), "/api/v1/help-requests/" + reqId, groupPath(w, "/dashboard"),
				groupPath(w, "/dashboard/members/" + w.a().id()), groupPath(w, "/risk-reports"),
				"/api/v1/tasks/" + w.t("a3").taskId(), "/api/v1/tasks/" + w.t("a3").taskId() + "/history", "/api/v1/notifications",
				groupPath(w, "/tasks") };
		for (var viewer : List.of(w.b(), w.c(), w.d())) {
			for (String path : publicReads) {
				assertThat(body(getOk(viewer, path))).as("%s đọc %s", viewer.name(), path).doesNotContain(SECRET);
			}
		}
		assertThat(notes.allText()).doesNotContain(SECRET);
		// bảng của TL chỉ mang id đề nghị riêng, không mang nội dung
		assertThat(body(getOk(w.lead(), groupPath(w, "/dashboard/members/" + w.a().id() + "/tasks")))).doesNotContain(SECRET);
		// Member không đọc được bảng task-theo-Member của TL
		assertThat(errorCode(getStatus(w.b(), groupPath(w, "/dashboard/members/" + w.a().id() + "/tasks"), 403)))
				.isEqualTo("LEAD_ONLY");
	}

	// ------------------------------------------------------------------------------------------------ B — Dashboard TL

	private record Board(World w, TaskRef a4, TaskRef a5, TaskRef a6, UUID srA2, UUID srA3) {
	}

	/**
	 * Member A có 6 task: a1 nộp đúng hạn chờ duyệt; a2 quá hạn + đã bấm Cần giúp đỡ; a3 chưa tới hạn + Cần giúp đỡ;
	 * a4 đã nghiệm thu (DONE); a5 đang làm bình thường; a6 thuộc kỳ SAU. Đồng hồ: sau hạn a1/a2, trước hạn a3 và giờ chốt.
	 */
	private Board boardWorld() throws Exception {
		World w = world();
		Instant closes = w.cycle().closesAt();
		fx.submission(w.pid(), w.t("a1"), 1, closes.minus(Duration.ofDays(2)));
		TaskRef a4 = fx.approvedTask(w.pid(), w.lead().id(), w.a().id(), w.cycle(), "EASY", closes.minus(Duration.ofDays(1)));
		UUID s4 = fx.submission(w.pid(), a4, 1, closes.minus(Duration.ofDays(2)));
		Instant reviewed = closes.plusSeconds(3600);
		fx.review(w.pid(), s4, w.lead().id(), "ACCEPTED", reviewed);
		jdbc.sql("""
				UPDATE task_review_rounds SET state = 'ACCEPTED', accepted_submission_id = :s, completed_at = :c, accepted_at = :r,
				       closed_at = :r
				 WHERE id = :id
				""").param("s", s4).param("c", DbTime.toDb(closes.minus(Duration.ofDays(2)))).param("r", DbTime.toDb(reviewed))
				.param("id", a4.roundId()).update();
		TaskRef a5 = fx.approvedTask(w.pid(), w.lead().id(), w.a().id(), w.cycle(), "MEDIUM", closes);
		TaskRef a6 = fx.approvedTask(w.pid(), w.lead().id(), w.a().id(), w.cycle2(), "MEDIUM",
				w.cycle2().closesAt().minus(Duration.ofDays(1)));
		clock.set(closes.minus(Duration.ofHours(19))); // sau hạn a1/a2 (closes − 1 ngày), trước hạn a3/a5 và giờ chốt
		UUID srA2 = uuid(privateRequest(w, "a2"), "$.id");
		UUID srA3 = uuid(privateRequest(w, "a3"), "$.id");
		return new Board(w, a4, a5, a6, srA2, srA3);
	}

	private MvcResult board(Board b) throws Exception {
		return getOk(b.w().lead(), groupPath(b.w(), "/dashboard/members/" + b.w().a().id() + "/tasks"));
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(MvcResult board, UUID taskId, String path) throws Exception {
		List<Object> found = JsonPath.read(body(board), "$.tasks[?(@.task.id=='" + taskId + "')]." + path);
		assertThat(found).as("task %s phải có đúng một dòng với %s", taskId, path).hasSize(1);
		return (T) found.get(0);
	}

	@Test
	void s2_B1_leadSeesEveryTaskOfTheMemberAcrossWeeksBacklogAndDone() throws Exception {
		Board b = boardWorld();
		World w = b.w();
		MvcResult r = board(b);
		List<String> ids = JsonPath.read(body(r), "$.tasks[*].task.id");
		assertThat(ids).containsExactlyInAnyOrder(w.t("a1").taskId().toString(), w.t("a2").taskId().toString(),
				w.t("a3").taskId().toString(), b.a4().taskId().toString(), b.a5().taskId().toString(), b.a6().taskId().toString());
		assertThat((String) at(r, "$.member.userId")).isEqualTo(w.a().id().toString());
		assertThat((Integer) at(r, "$.counts.OVERDUE") + (Integer) at(r, "$.counts.NEEDS_HELP") + (Integer) at(r, "$.counts.ACTIVE")
				+ (Integer) at(r, "$.counts.AWAITING_REVIEW") + (Integer) at(r, "$.counts.DONE")).isEqualTo(6);
		// kỳ này, kỳ sau và task đã hoàn thành cùng nằm trong MỘT danh sách; chỉ task của chính Member đó
		List<String> cycles = JsonPath.read(body(r), "$.tasks[*].task.cycle.id");
		assertThat(cycles).contains(w.cycle().id().toString(), w.cycle2().id().toString());
		List<String> others = JsonPath.read(body(getOk(w.lead(), groupPath(w, "/dashboard/members/" + w.b().id() + "/tasks"))),
				"$.tasks[*].task.id");
		assertThat(others).containsExactly(w.t("b1").taskId().toString());
		assertThat(errorCode(getStatus(w.lead(), groupPath(w, "/dashboard/members/" + UUID.randomUUID() + "/tasks"), 404)))
				.isEqualTo("NOT_FOUND");
		assertThat(errorCode(getStatus(fx.user("Ngoài"), groupPath(w, "/dashboard/members/" + w.a().id() + "/tasks"), 404)))
				.isEqualTo("NOT_A_GROUP_MEMBER");
	}

	@Test
	void s2_B2_overdueWithoutValidResultIsFirstAndOnTimePendingReviewIsNeverOverdue() throws Exception {
		Board b = boardWorld();
		World w = b.w();
		MvcResult r = board(b);
		List<String> ids = JsonPath.read(body(r), "$.tasks[*].task.id");
		assertThat(ids.get(0)).isEqualTo(w.t("a2").taskId().toString());
		assertThat((String) field(r, w.t("a2").taskId(), "section")).isEqualTo("OVERDUE");
		assertThat((Boolean) field(r, w.t("a2").taskId(), "overdue")).isTrue();
		assertThat((String) field(r, w.t("a2").taskId(), "task.deadline")).isEqualTo(w.t("a2").deadline().toString());
		// a1 đã nộp ĐÚNG hạn và đang chờ TL duyệt: không đỏ, không xếp vào nhóm quá hạn
		assertThat((Boolean) field(r, w.t("a1").taskId(), "overdue")).isFalse();
		assertThat((String) field(r, w.t("a1").taskId(), "section")).isEqualTo("AWAITING_REVIEW");
		// chưa tới hạn / kỳ sau / đã DONE không bị tính quá hạn
		for (UUID id : List.of(w.t("a3").taskId(), b.a5().taskId(), b.a6().taskId(), b.a4().taskId())) {
			assertThat((Boolean) field(r, id, "overdue")).as("task %s", id).isFalse();
		}
		assertThat((Integer) at(r, "$.counts.OVERDUE")).isEqualTo(1);
	}

	@Test
	void s2_B3_needsHelpGroupIsPrioritisedAndOverdueAndNeedsHelpIsASingleRow() throws Exception {
		Board b = boardWorld();
		World w = b.w();
		MvcResult r = board(b);
		// a2 vừa quá hạn vừa cần giúp: đúng MỘT dòng, nhóm OVERDUE (đỏ) + cờ needsHelp để hiện thêm nhãn "Cần hỗ trợ"
		List<String> ids = JsonPath.read(body(r), "$.tasks[*].task.id");
		assertThat(ids.stream().filter(w.t("a2").taskId().toString()::equals).count()).isEqualTo(1);
		assertThat((String) field(r, w.t("a2").taskId(), "section")).isEqualTo("OVERDUE");
		assertThat((Boolean) field(r, w.t("a2").taskId(), "needsHelp")).isTrue();
		assertThat((String) field(r, w.t("a2").taskId(), "supportRequestId")).isEqualTo(b.srA2().toString());
		// a3 chỉ cần giúp: nhóm ưu tiên ngay sau quá hạn, trước task bình thường
		assertThat((String) field(r, w.t("a3").taskId(), "section")).isEqualTo("NEEDS_HELP");
		assertThat((Boolean) field(r, w.t("a3").taskId(), "needsHelp")).isTrue();
		assertThat(ids.indexOf(w.t("a3").taskId().toString())).isLessThan(ids.indexOf(b.a5().taskId().toString()));
		assertThat((Boolean) field(r, b.a5().taskId(), "needsHelp")).isFalse();
		// TL xử lý riêng xong (RESOLVED) thì không còn cờ cần hỗ trợ
		postJson(w.lead(), "/api/v1/support-requests/" + b.srA3() + "/decision",
				"{\"action\":\"RESOLVED_PRIVATELY\",\"response\":\"Đã hướng dẫn riêng\",\"version\":0}", 200);
		MvcResult after = board(b);
		assertThat((Boolean) field(after, w.t("a3").taskId(), "needsHelp")).isFalse();
		assertThat((String) field(after, w.t("a3").taskId(), "section")).isEqualTo("ACTIVE");
	}

	@Test
	void s2_B4_doneTasksSinkToTheBottomAndSubmittedHasItsOwnGroup() throws Exception {
		Board b = boardWorld();
		World w = b.w();
		MvcResult r = board(b);
		List<String> sections = JsonPath.read(body(r), "$.tasks[*].section");
		assertThat(sections).containsExactly("OVERDUE", "NEEDS_HELP", "ACTIVE", "ACTIVE", "AWAITING_REVIEW", "DONE");
		List<String> ids = JsonPath.read(body(r), "$.tasks[*].task.id");
		assertThat(ids.get(ids.size() - 1)).isEqualTo(b.a4().taskId().toString());
		assertThat((Boolean) field(r, b.a4().taskId(), "done")).isTrue();
		assertThat((Boolean) field(r, w.t("a1").taskId(), "awaitingReview")).isTrue();
		assertThat((String) field(r, w.t("a1").taskId(), "task.taskStatus")).isEqualTo("SUBMITTED");
	}

	@Test
	void s2_B5_publishToWorkspaceIsOnlyOfferedForValidTasksAndOnlyToTheLead() throws Exception {
		Board b = boardWorld();
		World w = b.w();
		MvcResult r = board(b);
		for (UUID ok : List.of(w.t("a2").taskId(), w.t("a3").taskId(), b.a5().taskId())) {
			assertThat((Boolean) field(r, ok, "canPublishToWorkspace")).as("task %s", ok).isTrue();
		}
		assertThat((Boolean) field(r, w.t("a1").taskId(), "canPublishToWorkspace")).isFalse();
		assertThat((String) field(r, w.t("a1").taskId(), "publishBlockedCode")).isEqualTo("TASK_AWAITING_REVIEW");
		assertThat((Boolean) field(r, b.a4().taskId(), "canPublishToWorkspace")).isFalse();
		assertThat((String) field(r, b.a4().taskId(), "publishBlockedCode")).isEqualTo("TASK_ALREADY_DONE");
		// khớp POST /help-requests: nút bật thì đăng được, nút tắt thì server cũng chặn
		postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a1", w.a(), null), 409);
		MvcResult pub = postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a2", w.a(), b.srA2()), 201);
		MvcResult after = board(b);
		assertThat((String) field(after, w.t("a2").taskId(), "helpRequestId")).isEqualTo((String) at(pub, "$.id"));
		assertThat((String) field(after, w.t("a2").taskId(), "helpRequestStatus")).isEqualTo("OPEN");
		assertThat((Boolean) field(after, w.t("a2").taskId(), "canPublishToWorkspace")).isFalse();
		assertThat((String) field(after, w.t("a2").taskId(), "publishBlockedCode")).isEqualTo("HELP_REQUEST_ALREADY_OPEN");
		// OPEN chỉ là "đang tìm người": người thực hiện vẫn là Member gốc
		assertThat(effectiveAssignee(w.t("a2").roundId())).isEqualTo(w.a().id());
		assertThat((Boolean) field(after, w.t("a2").taskId(), "needsHelp")).isFalse(); // đề nghị riêng đã được xử lý bằng việc đăng
		// Member không dùng được bảng này; không ai ngoài TL đăng được
		assertThat(errorCode(getStatus(w.a(), groupPath(w, "/dashboard/members/" + w.a().id() + "/tasks"), 403))).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postIdem(w.a(), groupPath(w, "/help-requests"), publishJson(w, "a3", w.a(), null), 403)))
				.isEqualTo("LEAD_ONLY");
	}

	// ------------------------------------------------------------------------------------------------ C — Workspace & bàn giao

	@Test
	void s2_C4_pendingOffersDoNotHandOverAndUnselectedOrWithdrawnHelpersAreNeverPenalised() throws Exception {
		World w = world();
		activate(w);
		MvcResult pub = publish(w, "a3", w.a());
		UUID req = uuid(pub, "$.id");
		long v = version(pub);
		UUID offerB = uuid(offer(w.b(), req, v, 201), "$.id");
		offer(w.c(), req, v, 201);
		UUID offerD = uuid(offer(w.d(), req, v, 201), "$.id");

		// Đề nghị ≠ Được chọn: chưa có trách nhiệm, chưa đổi người thực hiện, chưa đổi quyền nhận điểm
		assertThat(effectiveAssignee(w.t("a3").roundId())).isEqualTo(w.a().id());
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM support_works WHERE help_request_id = :p1", req)).isZero();
		assertThat((String) at(getOk(w.lead(), "/api/v1/help-requests/" + req), "$.status")).isEqualTo("OPEN");

		postJson(w.b(), "/api/v1/support-offers/" + offerB + "/withdraw", "", 200);
		MvcResult detail = getOk(w.lead(), "/api/v1/help-requests/" + req);
		List<String> offerOfC = JsonPath.read(body(detail), "$.offers[?(@.helper.userId=='" + w.c().id() + "')].id");
		accept(w, req, UUID.fromString(offerOfC.get(0)), version(detail), 200);

		// B (đã rút) và D (không được chọn): không trừ điểm, không ghi nhận tiêu cực, vẫn đề nghị được việc khác
		for (var u : List.of(w.b(), w.d())) {
			MvcResult me = getOk(u, "/api/v1/groups/" + w.pid() + "/points/me");
			assertThat((String) at(me, "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
			assertThat((String) at(me, "$.potentialPoints.percentLabel")).isEqualTo("20,0000");
			assertThat((Integer) at(getOk(u, "/api/v1/groups/" + w.pid() + "/points/me/ledger"), "$.totalItems")).isZero();
		}
		assertThat(count("SELECT COUNT(*) FROM support_offers WHERE id = :p1 AND status = 'NOT_SELECTED'", offerD)).isEqualTo(1);
		assertThat(notes.ofType("SUPPORT_NOT_SELECTED")).isNotEmpty();
		assertThat(notes.ofType("SUPPORT_NOT_SELECTED").get(0).message()).doesNotContainIgnoringCase("từ chối")
				.doesNotContainIgnoringCase("không đạt");
		MvcResult next = postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a2", w.a(), null), 201);
		offer(w.d(), uuid(next, "$.id"), version(next), 201);
		assertQuotaInvariant(w);
	}

	@Test
	void s2_C7_handoffKeepsTheSameTaskAndHistoryAndChangesNoConfirmedPoints() throws Exception {
		World w = world();
		activate(w);
		long tasksBefore = count("SELECT COUNT(*) FROM tasks WHERE project_id = :p1", w.pid());
		long roundsBefore = count("SELECT COUNT(*) FROM task_review_rounds WHERE project_id = :p1", w.pid());
		publishAndAccept(w, "a3", w.a(), w.b());
		UUID taskId = w.t("a3").taskId();

		// không nhân bản task/vòng; cùng task_id, người gốc được giữ, người thực hiện hiện tại là Helper
		assertThat(count("SELECT COUNT(*) FROM tasks WHERE project_id = :p1", w.pid())).isEqualTo(tasksBefore);
		assertThat(count("SELECT COUNT(*) FROM task_review_rounds WHERE project_id = :p1", w.pid())).isEqualTo(roundsBefore);
		MvcResult detail = getOk(w.lead(), "/api/v1/tasks/" + taskId);
		assertThat((String) at(detail, "$.id")).isEqualTo(taskId.toString());
		assertThat((String) at(detail, "$.originalAssignee.userId")).isEqualTo(w.a().id().toString());
		assertThat((String) at(detail, "$.currentExecutor.userId")).isEqualTo(w.b().id().toString());
		assertThat((String) at(detail, "$.effectivePlan.approvalSource")).isEqualTo("TL_SUPPORT_HANDOFF");
		assertThat(count("SELECT COUNT(*) FROM task_plan_revisions WHERE round_id = :p1", w.t("a3").roundId())).isEqualTo(2); // lịch sử giữ nguyên

		// điểm ĐÃ XÁC NHẬN của cả hai không đổi lúc bàn giao; chỉ khả năng nhận đổi
		for (var u : List.of(w.a(), w.b())) {
			MvcResult me = getOk(u, "/api/v1/groups/" + w.pid() + "/points/me");
			assertThat((String) at(me, "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
			assertThat((Integer) at(getOk(u, "/api/v1/groups/" + w.pid() + "/points/me/ledger?group=POINTS_RECORDED"), "$.totalItems")).isZero();
		}
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertQuotaInvariant(w);
	}

	@Test
	void s2_C8_afterHandoffTheTaskLivesInTheHelpersMyTasksAndCheckpointNotTheOriginalMembers() throws Exception {
		World w = world();
		activate(w);
		publishAndAccept(w, "a3", w.a(), w.b());
		UUID taskId = w.t("a3").taskId();
		List<String> mineB = JsonPath.read(body(getOk(w.b(), groupPath(w, "/tasks?tab=MINE&size=100"))), "$.items[*].id");
		List<String> mineA = JsonPath.read(body(getOk(w.a(), groupPath(w, "/tasks?tab=MINE&size=100"))), "$.items[*].id");
		assertThat(mineB).contains(taskId.toString());
		assertThat(mineA).doesNotContain(taskId.toString());
		// checkpoint tuần: Helper phải báo cáo task này, người gốc không còn nghĩa vụ ở các kỳ tương lai
		String report = groupPath(w, "/checkpoint-cycles/" + w.cycle().id() + "/my-report");
		assertThat((List<String>) JsonPath.read(body(getOk(w.b(), report)), "$.items[*].taskId")).contains(taskId.toString());
		assertThat((List<String>) JsonPath.read(body(getOk(w.a(), report)), "$.items[*].taskId")).doesNotContain(taskId.toString());
		// bàn giao trong cùng task: không có "việc hỗ trợ" tách rời — Helper thấy ngay hành động nộp như task thường
		List<String> actions = JsonPath.read(body(getOk(w.b(), "/api/v1/tasks/" + taskId)), "$.allowedActions");
		assertThat(actions).contains("SUBMIT_RESULT", "UPDATE_PROGRESS");
		List<String> aActions = JsonPath.read(body(getOk(w.a(), "/api/v1/tasks/" + taskId)), "$.allowedActions");
		assertThat(aActions).doesNotContain("SUBMIT_RESULT", "UPDATE_PROGRESS", "START", "REPORT_CHECKPOINT");
	}

	// ------------------------------------------------------------------------------------------------ D — thực hiện & nghiệm thu

	@Test
	void s2_D1_helperSubmitsThroughTheSharedTaskPopupAndNothingIsScoredUntilApproval() throws Exception {
		World w = world();
		activate(w);
		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		UUID work = uuid(acc, "$.work.id");
		UUID round = w.t("a3").roundId();
		String plan = currentPlanId(w, w.b(), "a3");
		String path = "/api/v1/task-rounds/" + round + "/submissions";

		assertThat(errorCode(postIdem(w.a(), path, popupBody(plan), 403))).isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postIdem(w.b(), path, popupBody(UUID.randomUUID().toString()), 409))).isEqualTo("PLAN_CHANGED");
		MvcResult sub = postIdem(w.b(), path, popupBody(plan), 201);
		assertThat((String) at(sub, "$.task.taskStatus")).isEqualTo("SUBMITTED");
		UUID taskSub = uuid(sub, "$.submission.id");
		// cùng một bản nộp được liên kết với phần việc hỗ trợ (đóng góp chờ xác nhận) — không có bản rời
		assertThat(count("SELECT COUNT(*) FROM support_submissions WHERE work_id = :p1 AND task_submission_id = :p2", work, taskSub)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1 AND status = 'PENDING_CONFIRMATION'", work)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM task_submissions WHERE round_id = :p1", round)).isEqualTo(1);
		// Chưa tính điểm: không ledger, điểm xác nhận của cả hai vẫn 0
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat((String) at(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");

		// sau checkpoint TL duyệt đạt trên hàng đợi chung: đúng một khoản +P_t cho Helper, người gốc không nhận
		clock.set(w.cycle().closesAt().plusSeconds(3600));
		String review = "/api/v1/task-submissions/" + taskSub + "/review";
		MvcResult done = postIdem(w.lead(), review, "{\"decision\":\"ACCEPTED\"}", 200);
		assertThat((String) at(done, "$.task.taskStatus")).isEqualTo("DONE");
		assertThat((String) at(done, "$.award.recipient.userId")).isEqualTo(w.b().id().toString());
		assertThat((String) at(done, "$.award.points.percentLabel")).isEqualTo("+10,0000");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1 AND entry_type = 'AWARD'", w.pid())).isEqualTo(1);
		assertThat((String) at(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
		assertThat((String) at(getOk(w.lead(), "/api/v1/help-requests/" + uuid(acc, "$.helpRequest.id")), "$.status")).isEqualTo("COMPLETED");
		// duyệt lại (double-click/retry) không cộng đôi
		postIdem(w.lead(), review, "{\"decision\":\"ACCEPTED\"}", 200);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1 AND entry_type = 'AWARD'", w.pid())).isEqualTo(1);
		assertQuotaInvariant(w);
	}

	@Test
	void s2_D4_unreviewedOrRejectedResultNeverChangesConfirmedPointsAndKeepsTheSameTask() throws Exception {
		World w = world();
		activate(w);
		publishAndAccept(w, "a3", w.a(), w.b());
		UUID round = w.t("a3").roundId();
		MvcResult sub = postIdem(w.b(), "/api/v1/task-rounds/" + round + "/submissions", popupBody(currentPlanId(w, w.b(), "a3")), 201);
		UUID taskSub = uuid(sub, "$.submission.id");

		// TL chưa duyệt: giữ SUBMITTED, không tự ghi điểm hay tự đẩy sang kỳ mới
		clock.set(w.cycle().closesAt().plusSeconds(3600));
		assertThat((String) at(getOk(w.lead(), "/api/v1/tasks/" + w.t("a3").taskId()), "$.taskStatus")).isEqualTo("SUBMITTED");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();

		// chưa đạt: ghi lỗi, không đổi điểm đã xác nhận; chốt hạn xử lý tiếp giữ nguyên task/quỹ/người nhận
		postIdem(w.lead(), "/api/v1/task-submissions/" + taskSub + "/review",
				"{\"decision\":\"FIX_REQUIRED\",\"issueDescription\":\"Thiếu phần kiểm thử, cần bổ sung\"}", 200);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat((String) at(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
		MvcResult carry = postIdem(w.lead(), "/api/v1/task-rounds/" + round + "/carryover",
				"{\"targetCycleId\":\"" + w.cycle2().id() + "\",\"deadline\":\"" + w.cycle2().closesAt().minus(Duration.ofDays(1)) + "\"}", 201);
		assertThat((String) at(carry, "$.task.id")).isEqualTo(w.t("a3").taskId().toString());
		assertThat((String) at(carry, "$.task.currentExecutor.userId")).isEqualTo(w.b().id().toString());
		assertThat(count("SELECT COUNT(*) FROM task_point_budgets WHERE task_id = :p1", w.t("a3").taskId())).isEqualTo(1);
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.b().id());
		assertQuotaInvariant(w);
	}

	@Test
	void s2_D6_onlyTheLeadReviewsAndNeitherTheHelperNorTheOriginalMemberCan() throws Exception {
		World w = world();
		activate(w);
		publishAndAccept(w, "a3", w.a(), w.b());
		MvcResult sub = postIdem(w.b(), "/api/v1/task-rounds/" + w.t("a3").roundId() + "/submissions",
				popupBody(currentPlanId(w, w.b(), "a3")), 201);
		String review = "/api/v1/task-submissions/" + uuid(sub, "$.submission.id") + "/review";
		clock.set(w.cycle().closesAt().plusSeconds(3600));
		for (var u : List.of(w.b(), w.a(), w.c())) {
			MvcResult denied = postIdem(u, review, "{\"decision\":\"ACCEPTED\"}", 403);
			assertThat(errorCode(denied)).as("%s không được duyệt", u.name()).isIn("NOT_AUTHORIZED_REVIEWER", "SELF_ACTION_FORBIDDEN", "FORBIDDEN");
		}
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		postIdem(w.lead(), review, "{\"decision\":\"ACCEPTED\"}", 200);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1 AND entry_type = 'AWARD'", w.pid())).isEqualTo(1);
	}

	@Test
	void s2_D9_theOriginalMemberCannotTakeBackAHandedOverTask() throws Exception {
		World w = world();
		activate(w);
		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		UUID req = uuid(acc, "$.helpRequest.id");
		UUID taskId = w.t("a3").taskId();
		long taskVersion = ((Number) at(getOk(w.a(), "/api/v1/tasks/" + taskId), "$.version")).longValue();
		String plan = currentPlanId(w, w.a(), "a3");

		assertThat(errorCode(postIdem(w.a(), "/api/v1/task-rounds/" + w.t("a3").roundId() + "/submissions", popupBody(plan), 403))).isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postJson(w.a(), "/api/v1/tasks/" + taskId + "/start", "{\"version\":" + taskVersion + "}", 403))).isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postJson(w.a(), "/api/v1/tasks/" + taskId + "/support-requests", supportJson("Muốn lấy lại task này để tự làm"), 403))).isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postJson(w.a(), "/api/v1/help-requests/" + req + "/cancel", "{\"version\":9}", 403))).isEqualTo("LEAD_ONLY");
		assertThat(effectiveAssignee(w.t("a3").roundId())).isEqualTo(w.b().id());
		assertThat(currentRecipient(taskId)).isEqualTo(w.b().id());
	}

	@Test
	void s2_D8_afterTheLeadCancelsAHandoffTheOriginalMemberCanSubmitAgainAndTheHelperCannot() throws Exception {
		World w = world();
		activate(w);
		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		UUID work = uuid(acc, "$.work.id");
		postJson(w.b(), "/api/v1/support-works/" + work + "/unable", "{\"reason\":\"Không còn thời gian để làm\",\"version\":"
				+ ((Number) at(acc, "$.work.version")).longValue() + "}", 200);
		long v = ((Number) at(getOk(w.lead(), "/api/v1/support-works/" + work), "$.version")).longValue();
		postIdem(w.lead(), "/api/v1/support-works/" + work + "/resolve-discussion",
				"{\"resolution\":\"CANCEL_REQUEST\",\"note\":\"Hoàn trả cho người gốc\",\"version\":" + v + "}", 200);
		// quyết định có lịch sử; quyền nhận điểm quay lại người gốc; chưa có điểm nào bị thu hồi vì chưa từng ghi
		assertThat(effectiveAssignee(w.t("a3").roundId())).isEqualTo(w.a().id());
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat(count("SELECT COUNT(*) FROM support_work_events WHERE work_id = :p1", work)).isGreaterThanOrEqualTo(3);
		// người gốc nộp lại bằng popup của task như bình thường; Helper cũ không còn quyền
		String plan = currentPlanId(w, w.a(), "a3");
		String path = "/api/v1/task-rounds/" + w.t("a3").roundId() + "/submissions";
		assertThat(errorCode(postIdem(w.b(), path, popupBody(plan), 403))).isEqualTo("NOT_ASSIGNEE");
		MvcResult sub = postIdem(w.a(), path, popupBody(plan), 201);
		assertThat((String) at(sub, "$.task.taskStatus")).isEqualTo("SUBMITTED");
		assertThat(count("SELECT COUNT(*) FROM support_submissions WHERE work_id = :p1", work)).isZero();
	}

	@Test
	void s2_D10_aMissingMemberNeverForcesAnyoneAndNothingMovesWithoutTheLeadsExplicitAcceptance() throws Exception {
		World w = world();
		activate(w);
		// Member mất liên lạc: không có đề nghị riêng nào. TL vẫn đăng được; hệ thống không tự giao cho ai.
		MvcResult pub = publish(w, "a3", w.a());
		UUID req = uuid(pub, "$.id");
		offer(w.b(), req, version(pub), 201);
		clock.set(clock.instant().plus(Duration.ofHours(30)));
		assertThat(effectiveAssignee(w.t("a3").roundId())).isEqualTo(w.a().id());
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM support_works WHERE project_id = :p1", w.pid())).isZero();
		assertThat((String) at(getOk(w.lead(), "/api/v1/help-requests/" + req), "$.status")).isEqualTo("OPEN");
		// không ai ngoài người đăng chốt được; Member không tự nhận
		assertThat(errorCode(postIdem(w.b(), "/api/v1/help-requests/" + req + "/accept-offer",
				"{\"offerId\":\"" + UUID.randomUUID() + "\",\"version\":" + version(pub) + "}", 403))).isEqualTo("LEAD_ONLY");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
	}

	@Test
	void s2_D11_originalMemberHelperAndLeadCanReadTheHandoffHistoryButOthersCannot() throws Exception {
		World w = world();
		activate(w);
		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		String path = "/api/v1/support-works/" + uuid(acc, "$.work.id");
		for (var u : List.of(w.a(), w.b(), w.lead())) {
			MvcResult r = getOk(u, path);
			assertThat((String) at(r, "$.originalAssignee.userId")).isEqualTo(w.a().id().toString());
			assertThat((String) at(r, "$.helper.userId")).isEqualTo(w.b().id().toString());
			assertThat((String) at(r, "$.timeline[0].kind")).isEqualTo("OFFER_ACCEPTED");
			assertThat((Boolean) at(r, "$.pointRecorded")).isFalse();
		}
		assertThat(errorCode(getStatus(w.c(), path, 403))).isEqualTo("FORBIDDEN");
		// task cũng trỏ tới phần việc để hiển thị lịch sử ngay trong chi tiết task
		MvcResult task = getOk(w.a(), "/api/v1/tasks/" + w.t("a3").taskId());
		assertThat((String) at(task, "$.supportWorkId")).isEqualTo(uuid(acc, "$.work.id").toString());
		assertThat((String) at(task, "$.helpRequestId")).isEqualTo(uuid(acc, "$.helpRequest.id").toString());
	}

	@Test
	void s2_D12_handoffAfterACheckpointNeverRewritesThePreviousRoundOrItsSubmissions() throws Exception {
		World w = world();
		activate(w);
		UUID oldRound = w.t("a3").roundId();
		MvcResult pub = publish(w, "a3", w.a());
		UUID req = uuid(pub, "$.id");
		UUID offerId = uuid(offer(w.b(), req, version(pub), 201), "$.id");
		clock.set(w.cycle().closesAt().plusSeconds(1));
		MvcResult carry = postIdem(w.lead(), "/api/v1/task-rounds/" + oldRound + "/carryover",
				"{\"targetCycleId\":\"" + w.cycle2().id() + "\",\"deadline\":\"" + w.cycle2().closesAt().minus(Duration.ofDays(1)) + "\"}", 201);
		UUID newRound = uuid(carry, "$.targetRound.id");
		MvcResult fresh = getOk(w.lead(), "/api/v1/help-requests/" + req);
		postJson(w.b(), "/api/v1/support-offers/" + offerId + "/reconfirm", "{\"helpRequestVersion\":" + version(fresh) + "}", 200);
		accept(w, req, offerId, version(getOk(w.lead(), "/api/v1/help-requests/" + req)), 200);

		assertThat(effectiveAssignee(newRound)).isEqualTo(w.b().id());
		assertThat(effectiveAssignee(oldRound)).isEqualTo(w.a().id()); // kỳ cũ giữ nguyên người báo cáo
		assertThat(count("SELECT COUNT(*) FROM task_plan_revisions WHERE round_id = :p1 AND approval_source = 'TL_SUPPORT_HANDOFF'", oldRound)).isZero();
		assertThat(count("SELECT COUNT(*) FROM checkpoint_task_snapshots WHERE round_id = :p1 AND frozen_at IS NOT NULL AND assignee_id <> :p2", oldRound, w.a().id())).isZero();
		assertThat((String) at(getOk(w.lead(), "/api/v1/tasks/" + w.t("a3").taskId() + "?roundId=" + oldRound), "$.currentExecutor.userId"))
				.isEqualTo(w.a().id().toString());
	}
}
