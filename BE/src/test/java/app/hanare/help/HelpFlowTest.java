package app.hanare.help;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.hanare.points.RecordingNotifications;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.HanareTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** J: yêu cầu hỗ trợ công khai, offer, chấp thuận/bàn giao, huỷ, phần việc Helper (nộp / không thể / bất đồng). */
@HanareTest
@Import(RecordingNotifications.Config.class)
class HelpFlowTest extends HelpTestBase {

	// ------------------------------------------------------------------------------------------------ đăng

	@Test
	void memberCannotPublishAndLeadInputIsValidated() throws Exception {
		World w = world();
		String path = groupPath(w, "/help-requests");
		MvcResult member = postIdem(w.a(), path, publishJson(w, "a3", w.a(), null), 403);
		assertThat(errorCode(member)).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postIdem(fx.user("Ngoài"), path, publishJson(w, "a3", w.a(), null), 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(postJson(w.lead(), path, publishJson(w, "a3", w.a(), null), 400))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "a3", w.a(), null).replace("\"privacyChecked\":true", "\"privacyChecked\":false"), 400)))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "a3", w.a(), null).replace("Hỗ trợ toàn bộ task a3", "ab"), 400)))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "a3", w.b(), null), 422))).isEqualTo("BENEFICIARY_MISMATCH");
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "a3", w.a(), null).replace(w.t("a3").taskId().toString(),
				UUID.randomUUID().toString()), 404))).isEqualTo("NOT_FOUND");
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE project_id = :p1", w.pid())).isZero();
	}

	@Test
	void leadPublishesWholeTaskAtTheLockedPriceAndHoldsItWithOneReservation() throws Exception {
		World w = world();
		activate(w);
		MvcResult r = publish(w, "a3", w.a());
		assertThat((String) at(r, "$.status")).isEqualTo("OPEN");
		assertThat((String) at(r, "$.statusLabel")).isEqualTo("Đang tìm người nhận làm thay toàn bộ task");
		assertThat((String) at(r, "$.difficulty")).isEqualTo("HARD");
		assertThat((Integer) at(r, "$.points.pointUnits")).isEqualTo(100_000);
		assertThat((String) at(r, "$.points.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(r, "$.pointLabel")).isEqualTo("Khó · +10,0000 điểm % khi TL duyệt");
		assertThat((String) at(r, "$.myRoleInRequest")).isEqualTo("REQUESTER");
		assertThat((String) at(r, "$.beneficiary.name")).isEqualTo(w.a().name());
		assertThat((String) at(r, "$.deadline")).isEqualTo(w.cycle().closesAt().toString());
		assertThat((String) at(r, "$.resultApprover.name")).isEqualTo(w.lead().name());
		assertThat(count("SELECT COUNT(*) FROM task_point_reservations WHERE state = 'HELD' AND point_units = 100000 AND help_request_id = :p1",
				uuid(r, "$.id"))).isEqualTo(1);
		assertThat(count("SELECT advertised_point_units FROM help_requests WHERE id = :p1", uuid(r, "$.id"))).isEqualTo(100_000);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();

		List<app.hanare.notification.NotificationEvent> published = notes.ofType("HELP_PUBLISHED");
		assertThat(published).hasSize(1);
		assertThat(published.get(0).recipientIds()).hasSize(4).doesNotContain(w.lead().id());
		assertThat(published.get(0).message()).contains("Khó").contains("+10,0000 điểm % khi TL duyệt");

		// người gốc thấy P_t đang chờ chuyển nhưng khả năng nhận chưa đổi; chưa có bút toán trừ điểm
		MvcResult me = getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me");
		assertThat((String) at(me, "$.potentialPoints.percentLabel")).isEqualTo("20,0000");
		assertThat((String) at(me, "$.pendingTransfer.heldPoints.percentLabel")).isEqualTo("10,0000");
		assertThat((Integer) at(me, "$.pendingTransfer.openRequestCount")).isEqualTo(1);
		assertThat(errorCode(postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a3", w.a(), null), 409)))
				.isEqualTo("HELP_REQUEST_ALREADY_OPEN");
		assertThat(count("SELECT COUNT(*) FROM task_point_reservations WHERE state = 'HELD' AND budget_id = (SELECT id FROM task_point_budgets WHERE task_id = :p1)",
				w.t("a3").taskId())).isEqualTo(1);
		assertQuotaInvariant(w);
	}

	@Test
	void taskWithoutPolicyIsPublishedWithoutPointsAndWithoutReservation() throws Exception {
		World w = world();
		MvcResult r = publish(w, "a1", w.a());
		assertThat((Object) at(r, "$.points")).isNull();
		assertThat((Object) at(r, "$.pointLabel")).isNull();
		assertThat(count("SELECT COUNT(*) FROM task_point_reservations")).isGreaterThanOrEqualTo(0);
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE project_id = :p1 AND task_point_budget_id IS NULL", w.pid())).isEqualTo(1);
		assertThat(notes.ofType("HELP_PUBLISHED").get(0).message()).doesNotContain("điểm");
	}

	@Test
	void onlyUnsubmittedOrReworkTasksCanBeHandedOver() throws Exception {
		World w = world();
		activate(w);
		String path = groupPath(w, "/help-requests");
		// a1: đã nộp, TL chưa duyệt
		fx.submission(w.pid(), w.t("a1"), 1, Instant.parse("2026-11-17T03:00:00Z"));
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "a1", w.a(), null), 409))).isEqualTo("TASK_AWAITING_REVIEW");
		// a2: đã được chấp nhận
		UUID sub = fx.submission(w.pid(), w.t("a2"), 1, Instant.parse("2026-11-17T03:00:00Z"));
		fx.review(w.pid(), sub, w.lead().id(), "ACCEPTED", Instant.parse("2026-11-21T03:00:00Z"));
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "a2", w.a(), null), 409))).isEqualTo("TASK_ALREADY_DONE");
		// b1: đang có bản sửa kế hoạch chưa xử lý
		TaskRef b1 = w.t("b1");
		jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, base_revision_id, created_by, status)
				VALUES (:p, :r, 2, :base, :u, 'DRAFT')
				""").param("p", w.pid()).param("r", b1.roundId()).param("base", b1.planId()).param("u", w.b().id()).update();
		assertThat(errorCode(postIdem(w.lead(), path, publishJson(w, "b1", w.b(), null), 409))).isEqualTo("TASK_NOT_TRANSFERABLE");
		// a3: bị yêu cầu sửa (FIX_REQUIRED) -> được phép bàn giao
		TaskRef a3 = w.t("a3");
		UUID fixSub = fx.submission(w.pid(), a3, 1, Instant.parse("2026-11-17T03:00:00Z"));
		fx.review(w.pid(), fixSub, w.lead().id(), "FIX_REQUIRED", Instant.parse("2026-11-21T03:00:00Z"));
		jdbc.sql("UPDATE task_review_rounds SET state = 'CARRYOVER_REQUIRED' WHERE id = :r").param("r", a3.roundId()).update();
		postIdem(w.lead(), path, publishJson(w, "a3", w.a(), null), 201);
	}

	@Test
	void leadsOwnTaskNeedsTheIndependentApproverAsResultApprover() throws Exception {
		World w = world();
		MvcResult r = publish(w, "l1", w.lead());
		assertThat((String) at(r, "$.resultApprover.name")).isEqualTo(w.d().name());
		assertThat((String) at(r, "$.myRoleInRequest")).isEqualTo("REQUESTER");
		UUID reqId = uuid(r, "$.id");
		// người duyệt độc lập không thể tự đề nghị làm phần của task mình duyệt
		assertThat(errorCode(offer(w.d(), reqId, version(r), 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		offer(w.b(), reqId, version(r), 201);

		TaskRef l2 = fx.approvedTask(w.pid(), w.lead().id(), w.lead().id(), w.cycle(), "EASY", w.cycle().closesAt());
		MvcResult noApprover = postIdem(w.lead(), groupPath(w, "/help-requests"),
				"{\"taskId\":\"" + l2.taskId() + "\",\"beneficiaryId\":\"" + w.lead().id()
						+ "\",\"title\":\"Task của TL\",\"description\":\"Cần người hỗ trợ phần việc của TL\",\"privacyChecked\":true}", 409);
		assertThat(errorCode(noApprover)).isEqualTo("INDEPENDENT_REVIEWER_REQUIRED");
	}

	// ------------------------------------------------------------------------------------------------ đọc

	@Test
	void listAndDetailAreRoleAwareAndLinkTheTlDecisionWithoutPrivateContent() throws Exception {
		World w = world();
		activate(w);
		String secret = "Hoàn cảnh riêng tư của A";
		MvcResult sr = postJson(w.a(), "/api/v1/tasks/" + w.t("a3").taskId() + "/support-requests",
				"{\"obstacleType\":\"PERSONAL\",\"description\":\"" + secret + " nên khó làm kịp\",\"neededSupport\":\"Cần người làm thay\"}", 201);
		UUID srId = uuid(sr, "$.id");
		MvcResult pub = postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a3", w.a(), srId), 201);
		UUID reqId = uuid(pub, "$.id");
		assertThat((String) at(pub, "$.supportRequestId")).isEqualTo(srId.toString()); // chỉ TL thấy liên kết
		MvcResult sr2 = getOk(w.a(), "/api/v1/support-requests/" + srId);
		assertThat((String) at(sr2, "$.status")).isEqualTo("RESOLVED");
		assertThat((String) at(sr2, "$.decision")).isEqualTo("HELP_PUBLISHED");
		List<String> published = JsonPath.read(body(sr2), "$.publishedHelpRequestIds");
		assertThat(published).containsExactly(reqId.toString());
		assertThat(notes.ofType("SUPPORT_DECISION")).hasSize(1);
		assertThat(notes.ofType("SUPPORT_DECISION").get(0).recipientIds()).containsExactly(w.a().id());
		assertThat(notes.allText()).doesNotContain(secret);

		String list = groupPath(w, "/help-requests");
		MvcResult asB = getOk(w.b(), list);
		assertThat((Integer) at(asB, "$.totalItems")).isEqualTo(1);
		assertThat((String) at(asB, "$.items[0].myRoleInRequest")).isEqualTo("VIEWER");
		assertThat((Object) at(asB, "$.items[0].workId")).isNull();
		assertThat(body(asB)).doesNotContain(secret);
		assertThat((Integer) at(getOk(w.b(), list + "?difficulty=EASY"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?difficulty=HARD"), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(getOk(w.b(), list + "?minPointUnits=100001"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?maxPointUnits=100000&minPointUnits=100000"), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(getOk(w.b(), list + "?deadlineFrom=2026-12-01T00:00:00Z"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?deadlineTo=2026-12-01T00:00:00Z"), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(getOk(w.b(), list + "?maxEffortMinutes=100"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?maxEffortMinutes=240"), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(getOk(w.b(), list + "?tab=MY_OFFERS"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?tab=MY_WORK"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?tab=COMPLETED"), "$.totalItems")).isZero();
		assertThat(errorCode(getStatus(w.b(), list + "?tab=NOPE", 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(getStatus(fx.user("Ngoài"), list, 404))).isEqualTo("NOT_A_GROUP_MEMBER");

		MvcResult detailB = getOk(w.b(), "/api/v1/help-requests/" + reqId);
		assertThat((Integer) at(detailB, "$.offers.length()")).isZero();
		assertThat((Object) at(detailB, "$.supportRequestId")).isNull();
		List<String> actionsB = JsonPath.read(body(detailB), "$.allowedActions");
		assertThat(actionsB).containsExactly("OFFER_HELP");
		assertThat((String) at(detailB, "$.myImpact.potentialBefore.percentLabel")).isEqualTo("20,0000");
		assertThat((String) at(detailB, "$.myImpact.potentialAfter.percentLabel")).isEqualTo("30,0000");
		assertThat((String) at(detailB, "$.myImpact.delta.percentLabel")).isEqualTo("+10,0000");
		assertThat((String) at(detailB, "$.pointExplanation")).contains("chuyển toàn bộ");
		assertThat((String) at(detailB, "$.description")).contains("Hoàn thiện toàn bộ phần việc");
		assertThat(body(detailB)).doesNotContain(secret);
		MvcResult detailA = getOk(w.a(), "/api/v1/help-requests/" + reqId);
		assertThat((String) at(detailA, "$.myRoleInRequest")).isEqualTo("BENEFICIARY");
		assertThat((Object) at(detailA, "$.myImpact")).isNull();
		List<String> actionsLead = JsonPath.read(body(getOk(w.lead(), "/api/v1/help-requests/" + reqId)), "$.allowedActions");
		assertThat(actionsLead).containsExactly("EDIT", "CANCEL");
		assertThat(errorCode(getStatus(fx.user("Ngoài"), "/api/v1/help-requests/" + reqId, 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(getStatus(w.b(), "/api/v1/help-requests/" + UUID.randomUUID(), 404))).isEqualTo("NOT_FOUND");
	}

	// ------------------------------------------------------------------------------------------------ offer

	@Test
	void offersAreVoluntaryWithdrawableAndNeedReconfirmationAfterAnImportantEdit() throws Exception {
		World w = world();
		activate(w);
		MvcResult pub = publish(w, "a3", w.a());
		UUID reqId = uuid(pub, "$.id");
		long v = version(pub);

		MvcResult off = offer(w.b(), reqId, v, 201);
		UUID offerId = uuid(off, "$.id");
		assertThat((String) at(off, "$.status")).isEqualTo("PENDING");
		assertThat((Boolean) at(off, "$.needsReconfirmation")).isFalse();
		assertThat(notes.ofType("SUPPORT_OFFERED")).hasSize(1);
		assertThat(notes.ofType("SUPPORT_OFFERED").get(0).recipientIds()).containsExactly(w.lead().id());
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero(); // không cộng điểm lúc đề nghị

		assertThat(errorCode(offer(w.b(), reqId, v, 409))).isEqualTo("OFFER_ALREADY_EXISTS");
		assertThat(errorCode(offer(w.a(), reqId, v, 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		assertThat(errorCode(offer(w.lead(), reqId, v, 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		assertThat(errorCode(offer(w.c(), reqId, v + 5, 409))).isEqualTo("VERSION_CONFLICT");
		assertThat(errorCode(offer(fx.user("Ngoài"), reqId, v, 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(postJson(w.c(), "/api/v1/help-requests/" + reqId + "/offers",
				"{\"acknowledgedScope\":false,\"helpRequestVersion\":" + v + "}", 400))).isEqualTo("VALIDATION_FAILED");

		MvcResult leadView = getOk(w.lead(), "/api/v1/help-requests/" + reqId);
		assertThat((Integer) at(leadView, "$.offers.length()")).isEqualTo(1);
		assertThat((String) at(leadView, "$.allowedActions[0]")).isEqualTo("ACCEPT_OFFER");
		MvcResult bView = getOk(w.b(), "/api/v1/help-requests/" + reqId);
		assertThat((String) at(bView, "$.myOffer.status")).isEqualTo("PENDING");
		assertThat((Integer) at(getOk(w.c(), "/api/v1/help-requests/" + reqId), "$.offers.length()")).isZero();
		assertThat((Integer) at(getOk(w.b(), groupPath(w, "/help-requests?tab=MY_OFFERS")), "$.totalItems")).isEqualTo(1);

		// rút không cần lý do, không để lại ghi nhận tiêu cực; rút lại là idempotent; chỉ chủ offer được rút
		assertThat(errorCode(postJson(w.a(), "/api/v1/support-offers/" + offerId + "/withdraw", "", 403))).isEqualTo("FORBIDDEN");
		MvcResult withdrawn = postJson(w.b(), "/api/v1/support-offers/" + offerId + "/withdraw", "", 200);
		assertThat((String) at(withdrawn, "$.status")).isEqualTo("WITHDRAWN");
		assertThat((String) at(postJson(w.b(), "/api/v1/support-offers/" + offerId + "/withdraw", "", 200), "$.status")).isEqualTo("WITHDRAWN");
		assertThat(errorCode(postJson(w.b(), "/api/v1/support-offers/" + offerId + "/reconfirm", "{\"helpRequestVersion\":" + v + "}", 409)))
				.isEqualTo("OFFER_NOT_PENDING");
		MvcResult again = offer(w.b(), reqId, v, 201);
		UUID offer2 = uuid(again, "$.id");

		// đổi phạm vi quan trọng => offer cũ cần xác nhận lại trước khi được chọn
		assertThat(errorCode(patchJson(w.a(), "/api/v1/help-requests/" + reqId, "{\"description\":\"Mô tả mới đủ dài để hợp lệ\",\"version\":" + v + "}", 403)))
				.isEqualTo("LEAD_ONLY");
		assertThat(errorCode(patchJson(w.lead(), "/api/v1/help-requests/" + reqId, "{\"description\":\"Mô tả mới đủ dài để hợp lệ\",\"version\":" + (v + 9) + "}", 409)))
				.isEqualTo("VERSION_CONFLICT");
		assertThat(errorCode(patchJson(w.lead(), "/api/v1/help-requests/" + reqId, "{\"description\":\"Mô tả mới đủ dài để hợp lệ\"}", 400)))
				.isEqualTo("VALIDATION_FAILED");
		MvcResult patched = patchJson(w.lead(), "/api/v1/help-requests/" + reqId,
				"{\"description\":\"Mô tả mới đủ dài để hợp lệ\",\"title\":\"Tên mới của yêu cầu\",\"version\":" + v + "}", 200);
		long v2 = version(patched);
		assertThat(v2).isEqualTo(v + 1);
		assertThat((String) at(patched, "$.title")).isEqualTo("Tên mới của yêu cầu");
		assertThat((Integer) at(patched, "$.points.pointUnits")).isEqualTo(100_000); // điểm không sửa được
		List<Boolean> flags = JsonPath.read(body(getOk(w.lead(), "/api/v1/help-requests/" + reqId)),
				"$.offers[?(@.id=='" + offer2 + "')].needsReconfirmation");
		assertThat(flags).containsExactly(true);
		assertThat(errorCode(accept(w, reqId, offer2, v2, 409))).isEqualTo("OFFER_NEEDS_RECONFIRMATION");
		assertThat(errorCode(postJson(w.b(), "/api/v1/support-offers/" + offer2 + "/reconfirm", "{\"helpRequestVersion\":" + v + "}", 409)))
				.isEqualTo("VERSION_CONFLICT");
		MvcResult re = postJson(w.b(), "/api/v1/support-offers/" + offer2 + "/reconfirm", "{\"helpRequestVersion\":" + v2 + "}", 200);
		assertThat((Boolean) at(re, "$.needsReconfirmation")).isFalse();
		assertThat(notes.ofType("SUPPORT_OFFERED")).hasSize(3); // offer, offer lại, xác nhận lại
		accept(w, reqId, offer2, v2, 200);
		assertQuotaInvariant(w);
	}

	// ------------------------------------------------------------------------------------------------ chọn Helper

	@Test
	void acceptingAnOfferHandsOverExecutorAndWholePriceInOneTransactionWithoutWritingTheLedger() throws Exception {
		World w = world();
		activate(w);
		MvcResult pub = publish(w, "a3", w.a());
		UUID reqId = uuid(pub, "$.id");
		long v = version(pub);
		UUID offerB = uuid(offer(w.b(), reqId, v, 201), "$.id");
		UUID offerD = uuid(offer(w.d(), reqId, v, 201), "$.id");

		assertThat(errorCode(postIdem(w.a(), "/api/v1/help-requests/" + reqId + "/accept-offer",
				"{\"offerId\":\"" + offerB + "\",\"version\":" + v + "}", 403))).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postJson(w.lead(), "/api/v1/help-requests/" + reqId + "/accept-offer",
				"{\"offerId\":\"" + offerB + "\",\"version\":" + v + "}", 400))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat(errorCode(accept(w, reqId, offerB, v + 3, 409))).isEqualTo("VERSION_CONFLICT");

		MvcResult r = accept(w, reqId, offerB, v, 200);
		assertThat((String) at(r, "$.helpRequest.status")).isEqualTo("IN_PROGRESS");
		assertThat((String) at(r, "$.work.status")).isEqualTo("IN_PROGRESS");
		assertThat((String) at(r, "$.work.helper.name")).isEqualTo(w.b().name());
		assertThat((String) at(r, "$.work.pointLabel")).contains("+10,0000").contains("chưa ghi điểm");
		assertThat((Boolean) at(r, "$.work.pointRecorded")).isFalse();
		assertThat((Integer) at(r, "$.notSelectedOfferIds.length()")).isEqualTo(1);
		assertThat((String) at(r, "$.notSelectedOfferIds[0]")).isEqualTo(offerD.toString());
		assertThat((String) at(r, "$.task.currentExecutor.name")).isEqualTo(w.b().name());
		assertThat((String) at(r, "$.task.originalAssignee.name")).isEqualTo(w.a().name());
		assertThat((String) at(r, "$.task.points.currentRecipient.name")).isEqualTo(w.b().name());

		UUID taskId = w.t("a3").taskId();
		assertThat(reservationState(taskId)).isEqualTo("CONSUMED");
		assertThat(currentRecipient(taskId)).isEqualTo(w.b().id());
		assertThat(count("SELECT COUNT(*) FROM task_point_assignments WHERE status = 'SUPERSEDED' AND budget_id = (SELECT id FROM task_point_budgets WHERE task_id = :p1)", taskId)).isEqualTo(1);
		assertThat(effectiveAssignee(w.t("a3").roundId())).isEqualTo(w.b().id());
		assertThat(count("SELECT COUNT(*) FROM task_plan_revisions WHERE round_id = :p1 AND approval_source = 'TL_SUPPORT_HANDOFF' AND status = 'APPROVED'", w.t("a3").roundId())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM support_works WHERE help_request_id = :p1", reqId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM support_offers WHERE help_request_id = :p1 AND status = 'NOT_SELECTED'", reqId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero(); // không ghi điểm lúc chọn
		assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE project_id = :p1 AND action = 'HELP_OFFER_ACCEPTED'", w.pid())).isEqualTo(1);
		assertThat(notes.ofType("SUPPORT_ACCEPTED").get(0).recipientIds()).containsExactly(w.b().id());
		assertThat(notes.ofType("POINT_ASSIGNMENT_CHANGED").get(0).recipientIds()).containsExactlyInAnyOrder(w.a().id(), w.b().id());
		assertThat(notes.ofType("SUPPORT_NOT_SELECTED").get(0).recipientIds()).containsExactly(w.d().id());
		assertThat(notes.ofType("SUPPORT_NOT_SELECTED").get(0).message()).doesNotContainIgnoringCase("từ chối");

		// A mất khả năng nhận 10%, B có thể đạt 30% (chưa phải điểm đã ghi)
		MvcResult meA = getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me");
		assertThat((String) at(meA, "$.potentialPoints.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(meA, "$.transferredOut.points.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(meA, "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
		MvcResult meB = getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me");
		assertThat((String) at(meB, "$.potentialPoints.percentLabel")).isEqualTo("30,0000");
		assertThat((String) at(meB, "$.takenOver.points.percentLabel")).isEqualTo("10,0000");
		assertThat((Integer) at(getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me/tasks?status=TRANSFERRED_OUT"), "$.totalItems")).isEqualTo(1);
		assertThat((String) at(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me/tasks?source=TAKEN_OVER"), "$.items[0].source")).isEqualTo("TAKEN_OVER");

		// lặp lại / đua: chấp thuận cùng offer trả kết quả hiện có; người khác nhận trùng bị từ chối
		MvcResult repeat = accept(w, reqId, offerB, v, 200);
		assertThat((String) at(repeat, "$.work.id")).isEqualTo((String) at(r, "$.work.id"));
		assertThat(count("SELECT COUNT(*) FROM support_works WHERE help_request_id = :p1", reqId)).isEqualTo(1);
		assertThat(errorCode(accept(w, reqId, offerD, v, 409))).isEqualTo("HELP_ALREADY_MATCHED");
		assertThat(errorCode(offer(w.c(), reqId, v, 409))).isEqualTo("HELP_ALREADY_MATCHED");
		assertThat(errorCode(postJson(w.lead(), "/api/v1/help-requests/" + reqId + "/cancel", "{\"version\":" + (v + 1) + "}", 409)))
				.isEqualTo("HELP_REQUEST_HAS_HELPER");
		assertQuotaInvariant(w);
	}

	@Test
	void cancellingReleasesTheReservationAndPolitelyClosesPendingOffers() throws Exception {
		World w = world();
		activate(w);
		MvcResult pub = publish(w, "a3", w.a());
		UUID reqId = uuid(pub, "$.id");
		long v = version(pub);
		offer(w.b(), reqId, v, 201);
		String cancel = "/api/v1/help-requests/" + reqId + "/cancel";
		assertThat(errorCode(postJson(w.a(), cancel, "{\"version\":" + v + "}", 403))).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postJson(w.lead(), cancel, "{\"version\":" + (v + 4) + "}", 409))).isEqualTo("VERSION_CONFLICT");
		MvcResult done = postJson(w.lead(), cancel, "{\"reason\":\"Người gốc đã tự làm xong\",\"version\":" + v + "}", 200);
		assertThat((String) at(done, "$.status")).isEqualTo("CANCELLED");
		assertThat(reservationState(w.t("a3").taskId())).isEqualTo("RELEASED");
		assertThat(currentRecipient(w.t("a3").taskId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM support_offers WHERE help_request_id = :p1 AND status = 'NOT_SELECTED'", reqId)).isEqualTo(1);
		assertThat(notes.ofType("SUPPORT_NOT_SELECTED")).hasSize(1);
		assertThat(notes.ofType("SUPPORT_NOT_SELECTED").get(0).recipientIds()).containsExactly(w.b().id());
		assertThat(errorCode(offer(w.c(), reqId, v + 1, 409))).isEqualTo("HELP_REQUEST_NOT_OPEN");
		assertThat((String) at(postJson(w.lead(), cancel, "{\"version\":" + v + "}", 200), "$.status")).isEqualTo("CANCELLED"); // idempotent
		MvcResult mine = getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me");
		assertThat((String) at(mine, "$.pendingTransfer.heldPoints.percentLabel")).isEqualTo("0,0000");
		MvcResult ledger = getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me/ledger?group=ASSIGNMENT");
		List<String> kinds = JsonPath.read(body(ledger), "$.items[*].kind");
		assertThat(kinds).containsExactlyInAnyOrder("RESERVATION_HELD", "RESERVATION_RELEASED");
		assertThat((Integer) at(getOk(w.b(), groupPath(w, "/help-requests?tab=COMPLETED")), "$.totalItems")).isEqualTo(1);
		// sau khi hủy, task có thể đăng lại và lại giữ đúng một reservation
		postIdem(w.lead(), groupPath(w, "/help-requests"), publishJson(w, "a3", w.a(), null), 201);
		assertThat(count("SELECT COUNT(*) FROM task_point_reservations WHERE state = 'HELD' AND budget_id = (SELECT id FROM task_point_budgets WHERE task_id = :p1)", w.t("a3").taskId())).isEqualTo(1);
		assertQuotaInvariant(w);
	}

	// ------------------------------------------------------------------------------------------------ Helper nộp & TL duyệt

	@Test
	void helperSubmissionThenAcceptedReviewConfirmsOneContributionAndWritesOneAward() throws Exception {
		World w = world();
		activate(w);
		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		UUID workId = uuid(acc, "$.work.id");
		String workPath = "/api/v1/support-works/" + workId;

		MvcResult work = getOk(w.b(), workPath);
		assertThat((Boolean) at(work, "$.canSubmit")).isTrue();
		List<String> helperActions = JsonPath.read(body(work), "$.allowedActions");
		assertThat(helperActions).containsExactly("SUBMIT_RESULT", "REPORT_UNABLE");
		assertThat((String) at(work, "$.roundId")).isEqualTo(w.t("a3").roundId().toString());
		assertThat((String) at(work, "$.timeline[0].kind")).isEqualTo("OFFER_ACCEPTED");
		assertThat(errorCode(getStatus(w.c(), workPath, 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(getStatus(fx.user("Ngoài"), workPath, 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		getOk(w.a(), workPath); // người gốc xem được
		getOk(w.lead(), workPath);

		String submissions = workPath + "/submissions";
		assertThat(errorCode(postIdem(w.a(), submissions, evidenceJson(), 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(postJson(w.b(), submissions, evidenceJson(), 400))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat(errorCode(postIdem(w.b(), submissions, "{\"resultNote\":\"Đã hoàn thiện toàn bộ phần việc\",\"evidence\":[]}", 422)))
				.isEqualTo("EVIDENCE_REQUIRED");
		assertThat(errorCode(postIdem(w.b(), submissions, "{\"resultNote\":\"Đã hoàn thiện toàn bộ phần việc\",\"evidence\":[{\"url\":\"not-a-url\"}]}", 400)))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postIdem(w.b(), submissions, "{\"resultNote\":\"ngắn\",\"evidence\":[{\"url\":\"https://x.test/a\"}]}", 400)))
				.isEqualTo("VALIDATION_FAILED");

		MvcResult sub = helperSubmit(w.b(), workId, 201);
		assertThat((Integer) at(sub, "$.submission.revisionNo")).isEqualTo(1);
		assertThat((String) at(sub, "$.work.status")).isEqualTo("SUBMITTED");
		assertThat((String) at(sub, "$.work.contributionStatus")).isEqualTo("PENDING_CONFIRMATION");
		assertThat((Integer) at(sub, "$.submission.actualMinutes")).isEqualTo(180);
		UUID taskSub = uuid(sub, "$.submission.taskSubmissionId");
		assertThat(count("SELECT COUNT(*) FROM task_submissions WHERE id = :p1 AND submitted_by = (SELECT helper_id FROM support_works WHERE id = :p2)", taskSub, workId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1 AND status = 'PENDING_CONFIRMATION'", workId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE id = :p1 AND status = 'SUBMITTED'", uuid(acc, "$.helpRequest.id"))).isEqualTo(1);
		assertThat(notes.ofType("SUPPORT_SUBMITTED").get(0).recipientIds()).containsExactly(w.lead().id());
		assertThat(errorCode(helperSubmit(w.b(), workId, 409))).isEqualTo("WORK_NOT_SUBMITTABLE");
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero(); // nộp ≠ ghi điểm

		// sau giờ chốt TL xem được bản nộp để duyệt
		clock.set(w.cycle().closesAt().plusSeconds(600));
		MvcResult review = getOk(w.lead(), workPath);
		assertThat((Boolean) at(review, "$.canReviewNow")).isTrue();
		assertThat((String) at(review, "$.latestTaskSubmissionId")).isEqualTo(taskSub.toString());
		List<String> leadActions = JsonPath.read(body(review), "$.allowedActions");
		assertThat(leadActions).contains("REVIEW_RESULT");

		// TL chấp nhận: BE-B1 ghi quyết định rồi gọi cổng; gọi lặp không tạo thêm gì
		acceptBySubmission(w, w.t("a3").taskId(), w.t("a3").roundId(), taskSub, w.lead(), w.b(), w.cycle().closesAt());
		acceptBySubmission(w, w.t("a3").taskId(), w.t("a3").roundId(), taskSub, w.lead(), w.b(), w.cycle().closesAt());
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1", workId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1 AND status = 'CONFIRMED' AND confirmed_by = :p2", workId, w.lead().id())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(count("SELECT delta_units FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(100_000);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE recipient_id = :p1", w.b().id())).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE recipient_id = :p1", w.a().id())).isZero(); // người gốc không nhận lại
		assertThat(count("SELECT COUNT(*) FROM support_works WHERE id = :p1 AND status = 'APPROVED'", workId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE id = :p1 AND status = 'COMPLETED'", uuid(acc, "$.helpRequest.id"))).isEqualTo(1);
		assertThat(notes.ofType("POINTS_AWARDED")).hasSize(1);
		assertThat(notes.ofType("POINTS_AWARDED").get(0).recipientIds()).containsExactly(w.b().id());
		assertThat(notes.ofType("CONTRIBUTION_CONFIRMED")).isEmpty(); // gộp vào POINTS_AWARDED

		MvcResult done = getOk(w.b(), workPath);
		assertThat((String) at(done, "$.status")).isEqualTo("APPROVED");
		assertThat((Boolean) at(done, "$.pointRecorded")).isTrue();
		assertThat((String) at(done, "$.contribution.status")).isEqualTo("CONFIRMED");
		assertThat((String) at(done, "$.pointLabel")).isEqualTo("Đã ghi nhận +10,0000 điểm %");
		assertThat((String) at(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.confirmedPoints.percentLabel")).isEqualTo("0,0000");
		MvcResult entry = getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me/ledger?group=POINTS_RECORDED");
		assertThat((String) at(entry, "$.items[0].source")).isEqualTo("TAKEN_OVER");
		assertThat((String) at(entry, "$.items[0].workId")).isEqualTo(workId.toString());
		MvcResult detail = getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/ledger/" + at(entry, "$.items[0].ledgerEntryId"));
		assertThat((String) at(detail, "$.contribution.status")).isEqualTo("CONFIRMED");
		assertThat((String) at(detail, "$.originalAssignee.name")).isEqualTo(w.a().name());
		List<String> aKinds = JsonPath.read(body(getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me/ledger?group=ASSIGNMENT")), "$.items[*].kind");
		assertThat(aKinds).containsExactlyInAnyOrder("RESERVATION_HELD", "ASSIGNMENT_OUT");
		List<String> bKinds = JsonPath.read(body(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me/ledger?group=ASSIGNMENT")), "$.items[*].kind");
		assertThat(bKinds).containsExactly("ASSIGNMENT_IN");
		assertQuotaInvariant(w);
	}

	@Test
	void revisionRequestedKeepsPriceAndAfterCarryoverHelperResubmitsWithoutANewFund() throws Exception {
		World w = world();
		activate(w);
		MvcResult acc = publishAndAccept(w, "a3", w.a(), w.b());
		UUID workId = uuid(acc, "$.work.id");
		MvcResult sub = helperSubmit(w.b(), workId, 201);
		UUID taskSub1 = uuid(sub, "$.submission.taskSubmissionId");
		TaskRef a3 = w.t("a3");

		// TL yêu cầu sửa sau giờ chốt (BE-B1 ghi quyết định + đóng vòng chờ chuyển kỳ)
		Instant after = w.cycle().closesAt().plusSeconds(3600);
		clock.set(after);
		fx.review(w.pid(), taskSub1, w.lead().id(), "FIX_REQUIRED", after);
		jdbc.sql("UPDATE task_review_rounds SET state = 'CARRYOVER_REQUIRED' WHERE id = :r").param("r", a3.roundId()).update();
		MvcResult work = getOk(w.b(), "/api/v1/support-works/" + workId);
		assertThat((String) at(work, "$.status")).isEqualTo("REVISION_REQUESTED");
		assertThat((String) at(work, "$.submissions[0].reviewDecision")).isEqualTo("FIX_REQUIRED");
		assertThat((Boolean) at(work, "$.canSubmit")).isFalse();
		assertThat((String) at(work, "$.cannotSubmitReason.code")).isEqualTo("WORK_NOT_SUBMITTABLE");
		assertThat((Object) at(work, "$.roundId")).isNull(); // chờ TL chốt deadline xử lý tiếp
		assertThat(notes.ofType("REVISION_REQUESTED")).hasSize(1);
		assertThat(notes.ofType("REVISION_REQUESTED").get(0).recipientIds()).containsExactly(w.b().id());
		getOk(w.b(), "/api/v1/support-works/" + workId); // đọc lại không phát thêm
		assertThat(notes.ofType("REVISION_REQUESTED")).hasSize(1);
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE id = :p1 AND status = 'IN_PROGRESS'", uuid(acc, "$.helpRequest.id"))).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1 AND status = 'PENDING_CONFIRMATION'", workId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat(errorCode(helperSubmit(w.b(), workId, 409))).isEqualTo("WORK_NOT_SUBMITTABLE");

		// BE-B1 chuyển kỳ: vòng 2 ở kỳ 2, người thực hiện vẫn là Helper (revision hiệu lực của vòng 1 là TL_SUPPORT_HANDOFF)
		assertThat(effectiveAssignee(a3.roundId())).isEqualTo(w.b().id());
		UUID round2 = carryOver(w, a3, w.b().id());
		clock.set(w.cycle().closesAt().plusSeconds(7200));
		MvcResult again = getOk(w.b(), "/api/v1/support-works/" + workId);
		assertThat((Boolean) at(again, "$.canSubmit")).isTrue();
		assertThat((String) at(again, "$.roundId")).isEqualTo(round2.toString());
		MvcResult sub2 = helperSubmit(w.b(), workId, 201);
		assertThat((Integer) at(sub2, "$.submission.revisionNo")).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM task_submissions WHERE round_id = :p1", round2)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1", workId)).isEqualTo(1);

		acceptBySubmission(w, a3.taskId(), round2, uuid(sub2, "$.submission.taskSubmissionId"), w.lead(), w.b(), w.cycle2().closesAt());
		// sửa/nộp lại/chuyển kỳ không sinh quỹ mới: vẫn đúng một khoản P_t
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(count("SELECT delta_units FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isEqualTo(100_000);
		assertThat(count("SELECT COUNT(*) FROM contributions WHERE work_id = :p1 AND status = 'CONFIRMED'", workId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM task_point_budgets WHERE task_id = :p1", a3.taskId())).isEqualTo(1);
		assertQuotaInvariant(w);
	}

	/** Giả lập BE-B1 `carryover`: đóng vòng 1 (CARRIED_OVER), mở vòng 2 ở kỳ 2 với người thực hiện {@code assignee}. */
	private UUID carryOver(World w, TaskRef task, UUID assignee) {
		jdbc.sql("UPDATE task_review_rounds SET state = 'CARRIED_OVER', closed_at = now() WHERE id = :r").param("r", task.roundId()).update();
		UUID round2 = jdbc.sql("""
				INSERT INTO task_review_rounds (project_id, task_id, round_no, cycle_id, previous_round_id, reason, state, created_by)
				VALUES (:p, :t, 2, :c, :prev, 'FIX_REQUIRED', 'DRAFT', :u) RETURNING id
				""").param("p", w.pid()).param("t", task.taskId()).param("c", w.cycle2().id()).param("prev", task.roundId())
				.param("u", w.lead().id()).query(UUID.class).single();
		UUID plan2 = jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, proposed_cycle_id, assignee_id, title, description,
				    expected_output, start_at, deadline, difficulty, status, created_by, submitted_by, submitted_at, reviewed_by,
				    reviewed_at, approval_source)
				VALUES (:p, :r, 1, :c, :a, 'Sửa task', 'Phạm vi', 'Đầu ra', :s, :d, 'HARD', 'APPROVED', :l, :l, now(), :l, now(), 'TL_CARRYOVER')
				RETURNING id
				""").param("p", w.pid()).param("r", round2).param("c", w.cycle2().id()).param("a", assignee)
				.param("s", java.time.OffsetDateTime.ofInstant(w.cycle2().periodStart(), java.time.ZoneOffset.UTC))
				.param("d", java.time.OffsetDateTime.ofInstant(w.cycle2().closesAt(), java.time.ZoneOffset.UTC))
				.param("l", w.lead().id()).query(UUID.class).single();
		jdbc.sql("UPDATE task_review_rounds SET state = 'ACTIVE', effective_plan_id = :p, started_at = now() WHERE id = :r")
				.param("p", plan2).param("r", round2).update();
		return round2;
	}

	@Test
	void helperCanReportUnableAndLeadResolvesByResumingCancellingOrReopening() throws Exception {
		World w = world();
		activate(w);
		// --- RESUME rồi CANCEL_REQUEST (hoàn trả cho người gốc)
		MvcResult acc = publishAndAccept(w, "a2", w.a(), w.d());
		UUID workId = uuid(acc, "$.work.id");
		UUID reqId = uuid(acc, "$.helpRequest.id");
		String unable = "/api/v1/support-works/" + workId + "/unable";
		assertThat(errorCode(postIdem(w.b(), unable, "{\"reason\":\"Mình không làm được nữa\",\"version\":0}", 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(postIdem(w.d(), unable, "{\"reason\":\"ngắn\",\"version\":0}", 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postIdem(w.d(), unable, "{\"reason\":\"Mình không làm được nữa\",\"version\":9}", 409))).isEqualTo("VERSION_CONFLICT");
		MvcResult u = postIdem(w.d(), unable, "{\"reason\":\"Mình không làm được nữa\",\"version\":0}", 200);
		assertThat((String) at(u, "$.status")).isEqualTo("NEEDS_DISCUSSION");
		assertThat((String) at(u, "$.timeline[1].kind")).isEqualTo("UNABLE_REPORTED");
		assertThat(notes.ofType("SUPPORT_WORK_ISSUE").get(0).recipientIds()).containsExactly(w.lead().id());
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero(); // không trừ điểm
		assertThat(errorCode(postIdem(w.d(), unable, "{\"reason\":\"Mình không làm được nữa\",\"version\":1}", 409))).isEqualTo("WORK_INVALID_STATE");

		String resolve = "/api/v1/support-works/" + workId + "/resolve-discussion";
		long v = ((Number) at(u, "$.version")).longValue();
		String resume = "{\"resolution\":\"RESUME\",\"note\":\"Cho thêm thời gian\",\"version\":" + v + "}";
		assertThat(errorCode(postIdem(w.d(), resolve, resume, 403))).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postJson(w.lead(), resolve, resume, 400))).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		assertThat((String) at(postIdem(w.lead(), resolve, resume, 200), "$.status")).isEqualTo("IN_PROGRESS");
		MvcResult u2 = postIdem(w.d(), unable, "{\"reason\":\"Vẫn không thể hoàn thành\",\"version\":" + (v + 1) + "}", 200);
		long v2 = ((Number) at(u2, "$.version")).longValue();
		String cancel = "{\"resolution\":\"CANCEL_REQUEST\",\"note\":\"Trả lại cho người gốc\",\"version\":" + v2 + "}";
		MvcResult cancelled = postIdem(w.lead(), resolve, cancel, 200);
		assertThat((String) at(cancelled, "$.helpRequestStatus")).isEqualTo("CANCELLED");
		UUID taskId = w.t("a2").taskId();
		assertThat(currentRecipient(taskId)).isEqualTo(w.a().id());
		assertThat(effectiveAssignee(w.t("a2").roundId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM task_plan_revisions WHERE round_id = :p1 AND approval_source = 'TL_SUPPORT_HANDOFF'", w.t("a2").roundId())).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM support_offers WHERE help_request_id = :p1 AND status = 'WITHDRAWN'", reqId)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero();
		assertThat((String) at(postIdem(w.lead(), resolve, cancel, 200), "$.helpRequestStatus")).isEqualTo("CANCELLED"); // lặp lại
		assertThat(errorCode(postIdem(w.lead(), resolve, resume, 409))).isEqualTo("WORK_INVALID_STATE");
		assertThat(notes.ofType("POINT_ASSIGNMENT_CHANGED").stream().filter(e -> e.dedupKey().contains("assignment-returned")).toList())
				.hasSize(1);
		assertThat((String) at(getOk(w.a(), "/api/v1/groups/" + w.pid() + "/points/me"), "$.potentialPoints.percentLabel")).isEqualTo("20,0000");

		// --- REOPEN_REQUEST: yêu cầu mới OPEN giữ lại đúng P_t
		MvcResult acc2 = publishAndAccept(w, "a1", w.a(), w.b());
		UUID work2 = uuid(acc2, "$.work.id");
		MvcResult un = postIdem(w.b(), "/api/v1/support-works/" + work2 + "/unable", "{\"reason\":\"Quá tải tuần này\",\"version\":0}", 200);
		long v3 = ((Number) at(un, "$.version")).longValue();
		MvcResult reopened = postIdem(w.lead(), "/api/v1/support-works/" + work2 + "/resolve-discussion",
				"{\"resolution\":\"REOPEN_REQUEST\",\"note\":\"Mở lại để tìm người khác\",\"version\":" + v3 + "}", 200);
		UUID successor = uuid(reopened, "$.successorHelpRequestId");
		assertThat((String) at(reopened, "$.status")).isEqualTo("NEEDS_DISCUSSION"); // work giữ lại để lưu lịch sử
		assertThat(count("SELECT COUNT(*) FROM help_requests WHERE id = :p1 AND status = 'OPEN' AND advertised_point_units = 33333", successor)).isEqualTo(1);
		assertThat(currentRecipient(w.t("a1").taskId())).isEqualTo(w.a().id());
		assertThat(count("SELECT COUNT(*) FROM task_point_reservations WHERE state = 'HELD' AND budget_id = (SELECT id FROM task_point_budgets WHERE task_id = :p1)", w.t("a1").taskId())).isEqualTo(1);
		assertThat((Integer) at(getOk(w.c(), groupPath(w, "/help-requests")), "$.totalItems")).isEqualTo(1);
		long sv = version(getOk(w.c(), "/api/v1/help-requests/" + successor));
		UUID offerC = uuid(offer(w.c(), successor, sv, 201), "$.id");
		accept(w, successor, offerC, sv, 200);
		assertThat(currentRecipient(w.t("a1").taskId())).isEqualTo(w.c().id());
		assertQuotaInvariant(w);
	}

	@Test
	void unknownRoutesAreProtectedByAuthentication() throws Exception {
		World w = world();
		call(null, post(groupPath(w, "/help-requests")).contentType(MediaType.APPLICATION_JSON).content("{}"), 401);
		call(null, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/support-works/" + UUID.randomUUID()), 401);
	}
}
