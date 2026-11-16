package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import app.hanare.notification.NotificationEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Mục F: tạo/giao task, kế hoạch DRAFT→PENDING_APPROVAL→APPROVED/CHANGES_REQUESTED, revision, withdraw, history, tab. */
class TaskPlanTest extends B1Base {

	@Test
	void leadCreatesTaskAndAssigneeIsNotified() throws Exception {
		World w = world();
		MvcResult t = createTask(w, w.a(), "Thiết kế API", "HARD", DL);
		assertThat((String) at(t, "$.planStatus")).isEqualTo("DRAFT");
		assertThat((Boolean) at(t, "$.committed")).isFalse();
		assertThat((String) at(t, "$.taskStatus")).isEqualTo("TODO");
		assertThat((String) at(t, "$.difficulty")).isEqualTo("HARD");
		assertThat((String) at(t, "$.currentExecutor.name")).isEqualTo(w.a().name());
		assertThat((Object) at(t, "$.pendingRevision.status")).isEqualTo("DRAFT");
		NotificationEvent e = notes.of("TASK_ASSIGNED").getFirst();
		assertThat(e.recipientIds()).containsExactly(w.a().id());
		assertThat(e.dedupKey()).isEqualTo("task:" + at(t, "$.id") + ":assigned");
		// người thực hiện thấy EDIT_PLAN + SUBMIT_PLAN; TL thấy EDIT_PLAN nhưng không SUBMIT_PLAN
		MvcResult asA = GET("/api/v1/tasks/" + at(t, "$.id"), w.a(), 200);
		assertThat((java.util.List<String>) at(asA, "$.allowedActions")).contains("EDIT_PLAN", "SUBMIT_PLAN").doesNotContain("REVIEW_PLAN");
		MvcResult asLead = GET("/api/v1/tasks/" + at(t, "$.id"), w.lead(), 200);
		assertThat((java.util.List<String>) at(asLead, "$.allowedActions")).contains("EDIT_PLAN").doesNotContain("SUBMIT_PLAN");
	}

	@Test
	void createRulesAndPermissions() throws Exception {
		World w = world();
		String url = "/api/v1/groups/" + w.pid() + "/tasks";
		// Member không được giao task
		POST(url, w.a(), body("title", "Abc", "assigneeId", w.b().id(), "cycleId", w.c1().id(), "description", "d", "expectedOutput", "o",
				"startAt", "2026-11-16T01:00:00Z", "deadline", DL.toString(), "difficulty", "EASY"), 403);
		// người ngoài nhóm -> 404
		var outsider = fx.user("Outsider");
		POST(url, outsider, "{}", 404);
		// deadline đúng giờ chốt thuộc kỳ 1; sau giờ chốt 1ms thuộc kỳ sau -> ngoài kỳ đã chọn
		MvcResult atCutoff = createTask(w, w.a(), "Sát giờ chốt", "EASY", w.c1().closesAt());
		assertThat((String) at(atCutoff, "$.cycle.id")).isEqualTo(w.c1().id().toString());
		MvcResult sunday = POST(url, w.lead(), body("title", "Chủ nhật sau cutoff", "assigneeId", w.a().id(), "cycleId", w.c1().id(),
				"description", "d", "expectedOutput", "o", "startAt", "2026-11-16T01:00:00Z",
				"deadline", Instant.parse("2026-11-22T15:00:00Z").toString(), "difficulty", "EASY"), 422);
		assertThat(code(sunday)).isEqualTo("DEADLINE_OUTSIDE_CYCLE");
		// nhưng đúng kỳ 2 thì hợp lệ
		MvcResult c2 = POST(url, w.lead(), body("title", "Thuộc kỳ sau", "assigneeId", w.a().id(), "cycleId", w.c2().id(), "description", "d",
				"expectedOutput", "o", "startAt", "2026-11-21T01:00:00Z", "deadline", "2026-11-22T15:00:00Z", "difficulty", "EASY"), 201);
		assertThat((Integer) at(c2, "$.cycle.sequenceNo")).isEqualTo(2);
		assertThat(code(POST(url, w.lead(), body("title", "Sai thứ tự", "assigneeId", w.a().id(), "cycleId", w.c1().id(), "description", "d",
				"expectedOutput", "o", "startAt", "2026-11-19T01:00:00Z", "deadline", "2026-11-18T01:00:00Z", "difficulty", "EASY"), 422)))
				.isEqualTo("START_AFTER_DEADLINE");
		assertThat(code(POST(url, w.lead(), body("title", "Người lạ", "assigneeId", outsider.id(), "cycleId", w.c1().id(), "description", "d",
				"expectedOutput", "o", "startAt", "2026-11-16T01:00:00Z", "deadline", DL.toString(), "difficulty", "EASY"), 422)))
				.isEqualTo("ASSIGNEE_NOT_IN_GROUP");
		POST(url, w.lead(), body("title", "ab", "assigneeId", w.a().id(), "cycleId", w.c1().id(), "description", "d", "expectedOutput", "o",
				"startAt", "2026-11-16T01:00:00Z", "deadline", DL.toString(), "difficulty", "EASY"), 400);
		// kỳ đã đóng thì không giao thêm
		clock.set(w.c1().closesAt().plusSeconds(5));
		assertThat(code(POST(url, w.lead(), body("title", "Muộn rồi", "assigneeId", w.a().id(), "cycleId", w.c1().id(), "description", "d",
				"expectedOutput", "o", "startAt", "2026-11-16T01:00:00Z", "deadline", DL.toString(), "difficulty", "EASY"), 409)))
				.isEqualTo("CYCLE_CLOSED");
	}

	@Test
	void planLifecycleDraftSubmitChangesRequestedApprove() throws Exception {
		World w = world();
		MvcResult t = createTask(w, w.a(), "Kế hoạch A", "MEDIUM", DL);
		UUID taskId = uuidAt(t, "$.id");
		UUID planId = uuidAt(t, "$.pendingRevision.id");
		long v0 = longAt(t, "$.pendingRevision.version");
		// người khác không sửa/gửi được
		PATCH("/api/v1/plans/" + planId, w.b(), body("title", "Hack", "version", v0), 403);
		POST("/api/v1/plans/" + planId + "/submit", w.b(), body("version", v0), 403);
		// người thực hiện sửa nháp (version cũ -> 409)
		MvcResult p1 = PATCH("/api/v1/plans/" + planId, w.a(), body("title", "Kế hoạch A (sửa)", "difficulty", "HARD", "version", v0), 200);
		assertThat((String) at(p1, "$.plan.title")).isEqualTo("Kế hoạch A (sửa)");
		assertThat((String) at(p1, "$.task.difficulty")).isEqualTo("HARD");
		assertThat(code(PATCH("/api/v1/plans/" + planId, w.a(), body("title", "Cũ rồi", "version", v0), 409))).isEqualTo("VERSION_CONFLICT");
		long v1 = longAt(p1, "$.plan.version");
		// gửi duyệt -> PENDING_APPROVAL, TL được thông báo, không sửa được nữa
		MvcResult s = POST("/api/v1/plans/" + planId + "/submit", w.a(), body("version", v1), 200);
		assertThat((String) at(s, "$.plan.status")).isEqualTo("PENDING_APPROVAL");
		assertThat((String) at(s, "$.task.planStatus")).isEqualTo("PENDING_APPROVAL");
		NotificationEvent sub = notes.of("PLAN_SUBMITTED").getFirst();
		assertThat(sub.recipientIds()).containsExactly(w.lead().id());
		assertThat(sub.entityId()).isEqualTo(planId);
		long v2 = longAt(s, "$.plan.version");
		assertThat(code(PATCH("/api/v1/plans/" + planId, w.a(), body("title", "x y z", "version", v2), 409))).isEqualTo("PLAN_NOT_EDITABLE");
		assertThat(code(POST("/api/v1/plans/" + planId + "/submit", w.a(), body("version", v2), 409))).isEqualTo("INVALID_PLAN_STATE");
		// Member không duyệt được
		POST("/api/v1/plans/" + planId + "/review", w.b(), body("decision", "APPROVED", "version", v2), 403);
		// trả lại thiếu lý do -> 400; có lý do -> CHANGES_REQUESTED + thông báo
		POST("/api/v1/plans/" + planId + "/review", w.lead(), body("decision", "CHANGES_REQUESTED", "version", v2), 400);
		MvcResult cr = POST("/api/v1/plans/" + planId + "/review", w.lead(),
				body("decision", "CHANGES_REQUESTED", "note", "Thiếu đầu ra cụ thể", "version", v2), 200);
		assertThat((String) at(cr, "$.plan.status")).isEqualTo("CHANGES_REQUESTED");
		assertThat((String) at(cr, "$.plan.reviewNote")).isEqualTo("Thiếu đầu ra cụ thể");
		assertThat(notes.of("PLAN_CHANGES_REQUESTED").getFirst().recipientIds()).containsExactly(w.a().id());
		// lặp lại cùng quyết định -> 200 (replay), quyết định khác -> 409
		POST("/api/v1/plans/" + planId + "/review", w.lead(), body("decision", "CHANGES_REQUESTED", "note", "Thiếu đầu ra cụ thể", "version", v2), 200);
		assertThat(code(POST("/api/v1/plans/" + planId + "/review", w.lead(), body("decision", "APPROVED", "version", v2), 409)))
				.isEqualTo("INVALID_PLAN_STATE");
		// sửa lại và gửi lần 2 (thông báo mới), rồi duyệt
		long v3 = longAt(cr, "$.plan.version");
		MvcResult p2 = PATCH("/api/v1/plans/" + planId, w.a(), body("expectedOutput", "OpenAPI + 12 endpoint", "version", v3), 200);
		MvcResult s2 = POST("/api/v1/plans/" + planId + "/submit", w.a(), body("version", longAt(p2, "$.plan.version")), 200);
		assertThat(notes.of("PLAN_SUBMITTED")).hasSize(2);
		MvcResult ok = POST("/api/v1/plans/" + planId + "/review", w.lead(), body("decision", "APPROVED", "version", longAt(s2, "$.plan.version")), 200);
		assertThat((String) at(ok, "$.plan.status")).isEqualTo("APPROVED");
		assertThat((Boolean) at(ok, "$.plan.isEffective")).isTrue();
		assertThat((String) at(ok, "$.task.planStatus")).isEqualTo("APPROVED");
		assertThat((Boolean) at(ok, "$.task.committed")).isTrue();
		assertThat((String) at(ok, "$.task.deadline")).isEqualTo(DL.toString());
		assertThat(notes.of("PLAN_APPROVED").getFirst().recipientIds()).containsExactly(w.a().id());
		// lịch sử task (mới nhất trước)
		MvcResult h = GET("/api/v1/tasks/" + taskId + "/history", w.b(), 200);
		assertThat((java.util.List<String>) at(h, "$.items[*].kind")).contains("TASK_CREATED", "PLAN_SUBMITTED", "PLAN_APPROVED", "PLAN_CHANGES_REQUESTED", "PLAN_SAVED");
		assertThat(jdbc.sql("SELECT state FROM task_review_rounds WHERE task_id = :t").param("t", taskId).query(String.class).single()).isEqualTo("ACTIVE");
	}

	@Test
	void revisionAfterApprovalKeepsOldPlanEffectiveUntilApproved() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Có revision", "HARD", DL);
		Instant newDeadline = DL.plusSeconds(24 * 3600); // vẫn trong kỳ 1 (20/11 13:00Z)
		MvcResult rev = POST("/api/v1/tasks/" + t.taskId() + "/plan-revisions", w.a(),
				body("changeReason", "Cần thêm 1 ngày", "deadline", newDeadline.toString(), "submitNow", true), 201);
		assertThat((String) at(rev, "$.plan.status")).isEqualTo("PENDING_APPROVAL");
		assertThat((String) at(rev, "$.plan.changeReason")).isEqualTo("Cần thêm 1 ngày");
		assertThat((String) at(rev, "$.plan.changes[0].field")).isEqualTo("deadline");
		assertThat((String) at(rev, "$.plan.changes[0].oldValue")).isEqualTo(DL.toString());
		assertThat((String) at(rev, "$.task.pendingRevisionStatus")).isEqualTo("PENDING_APPROVAL");
		// bản đã duyệt vẫn là cam kết: deadline không đổi
		assertThat((String) at(rev, "$.task.deadline")).isEqualTo(DL.toString());
		assertThat(notes.of("PLAN_CHANGE_SUBMITTED")).hasSize(1);
		assertThat(notes.of("PLAN_SUBMITTED")).hasSize(1); // chỉ lần đầu của kế hoạch gốc
		UUID rid = uuidAt(rev, "$.plan.id");
		assertThat(code(POST("/api/v1/tasks/" + t.taskId() + "/plan-revisions", w.a(), body("changeReason", "Lại đổi", "difficulty", "EASY"), 409)))
				.isEqualTo("REVISION_ALREADY_IN_FLIGHT");
		// không thay đổi gì -> 422; người khác -> 403
		POST("/api/v1/tasks/" + t.taskId() + "/plan-revisions", w.b(), body("changeReason", "Không phải của tôi", "difficulty", "EASY"), 403);
		// rút lại rồi đề xuất sửa nháp
		MvcResult wd = POST("/api/v1/plans/" + rid + "/withdraw", w.a(), body("version", longAt(rev, "$.plan.version")), 200);
		assertThat((String) at(wd, "$.plan.status")).isEqualTo("DRAFT");
		MvcResult s = POST("/api/v1/plans/" + rid + "/submit", w.a(), body("version", longAt(wd, "$.plan.version")), 200);
		MvcResult ok = POST("/api/v1/plans/" + rid + "/review", w.lead(), body("decision", "APPROVED", "version", longAt(s, "$.plan.version")), 200);
		assertThat((String) at(ok, "$.task.deadline")).isEqualTo(newDeadline.toString());
		assertThat((String) at(ok, "$.task.originalDeadline")).isEqualTo(DL.toString());
		MvcResult d = GET("/api/v1/tasks/" + t.taskId(), w.a(), 200);
		assertThat((String) at(d, "$.effectivePlan.id")).isEqualTo(rid.toString());
		assertThat((Integer) at(d, "$.effectivePlan.revisionNo")).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM task_plan_revisions WHERE round_id = :r AND status = 'APPROVED'").param("r", t.roundId())
				.query(Long.class).single()).isEqualTo(2L);
		// không đổi gì -> NO_CHANGES_PROPOSED
		assertThat(code(POST("/api/v1/tasks/" + t.taskId() + "/plan-revisions", w.a(), body("changeReason", "Không đổi gì cả"), 422)))
				.isEqualTo("NO_CHANGES_PROPOSED");
	}

	@Test
	void startProgressAndListTabs() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Làm việc", "EASY", DL);
		createTask(w, w.b(), "Chưa duyệt", "MEDIUM", DL.plusSeconds(3600));
		MvcResult d = GET("/api/v1/tasks/" + t.taskId(), w.a(), 200);
		assertThat((java.util.List<String>) at(d, "$.allowedActions")).contains("START", "SUBMIT_RESULT", "PROPOSE_CHANGE", "REPORT_CHECKPOINT");
		MvcResult st = POST("/api/v1/tasks/" + t.taskId() + "/start", w.a(), body("version", longAt(d, "$.version")), 200);
		assertThat((String) at(st, "$.taskStatus")).isEqualTo("IN_PROGRESS");
		POST("/api/v1/tasks/" + t.taskId() + "/start", w.b(), body("version", 0), 403);
		MvcResult pr = POST("/api/v1/tasks/" + t.taskId() + "/progress", w.a(),
				body("note", "Đang chờ dữ liệu mẫu", "riskStatus", "AT_RISK", "expectedCompletionAt", "2026-11-21T13:00:00Z"), 201);
		assertThat((String) at(pr, "$.riskStatus")).isEqualTo("AT_RISK");
		MvcResult after = GET("/api/v1/tasks/" + t.taskId(), w.lead(), 200);
		assertThat((String) at(after, "$.riskStatus")).isEqualTo("AT_RISK");
		assertThat((String) at(after, "$.lastProgress.note")).isEqualTo("Đang chờ dữ liệu mẫu");
		String base = "/api/v1/groups/" + w.pid() + "/tasks";
		assertThat((Integer) at(GET(base, w.b(), 200), "$.totalItems")).isEqualTo(2);
		assertThat((Integer) at(GET(base + "?tab=MINE", w.a(), 200), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(GET(base + "?tab=MINE", w.c(), 200), "$.totalItems")).isEqualTo(0);
		assertThat((Integer) at(GET(base + "?tab=NEEDS_EDIT", w.b(), 200), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(GET(base + "?tab=PENDING_APPROVAL", w.b(), 200), "$.totalItems")).isEqualTo(0);
		assertThat((Integer) at(GET(base + "?tab=NEEDS_ATTENTION", w.b(), 200), "$.totalItems")).isEqualTo(1); // AT_RISK
		assertThat((Integer) at(GET(base + "?difficulty=MEDIUM&q=chưa", w.b(), 200), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(GET(base + "?cycleId=" + w.c2().id(), w.b(), 200), "$.totalItems")).isEqualTo(0);
		assertThat((String) at(GET(base, w.b(), 200), "$.items[0].title")).isEqualTo("Làm việc"); // deadline sớm hơn
		GET(base, fx.user("Người ngoài"), 404);
		GET("/api/v1/tasks/" + UUID.randomUUID(), w.a(), 404);
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base).header("Authorization", auth.bearer(w.a())))
				.andExpect(jsonPath("$.items[0].isLate").value(false)).andExpect(jsonPath("$.items[0].committed").value(true));
	}

	@Test
	void independentReviewerOnlyForLeadsOwnTask() throws Exception {
		World w = world();
		Task own = approvedTask(w, w.lead(), "Việc của TL", "HARD", DL);
		Task other = approvedTask(w, w.a(), "Việc của A", "EASY", DL);
		String url = "/api/v1/tasks/" + own.taskId() + "/independent-reviewer";
		PUT(url, w.a(), body("reviewerUserId", w.b().id()), 403);
		assertThat(code(PUT(url, w.lead(), body("reviewerUserId", w.lead().id()), 400))).isEqualTo("VALIDATION_FAILED");
		MvcResult ok = PUT(url, w.lead(), body("reviewerUserId", w.b().id()), 200);
		assertThat((String) at(ok, "$.independentReviewer.name")).isEqualTo(w.b().name());
		PUT("/api/v1/tasks/" + other.taskId() + "/independent-reviewer", w.lead(), body("reviewerUserId", w.b().id()), 400);
		MvcResult cleared = PUT(url, w.lead(), body("reviewerUserId", null), 200);
		assertThat((Object) at(cleared, "$.independentReviewer")).isNull();
	}
}
