package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Mục G: nộp minh chứng, duyệt kết quả (idempotent, quyền), chuyển kỳ, hàng đợi — theo ví dụ spec 0.8 / 12.10. */
class TaskResultTest extends B1Base {

	static final Instant FRI_DEADLINE = Instant.parse("2026-11-20T13:00:00Z"); // 20:00 thứ Sáu giờ VN
	static final Instant AT_1930 = Instant.parse("2026-11-20T12:30:00Z"); // 19:30 giờ VN

	private String reviewUrl(UUID submissionId) {
		return "/api/v1/task-submissions/" + submissionId + "/review";
	}

	private String accept() {
		return body("decision", "ACCEPTED");
	}

	@Test
	void submittedOnTimeStaysOnTimeEvenIfLeadReviewsNextDay() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Thiết kế API", "HARD", FRI_DEADLINE);
		clock.set(AT_1930);
		MvcResult sub = submitResult(t, w.a(), 201);
		assertThat((Boolean) at(sub, "$.submission.isOnTime")).isTrue();
		assertThat((Boolean) at(sub, "$.submission.isReviewTarget")).isTrue();
		assertThat((String) at(sub, "$.task.taskStatus")).isEqualTo("SUBMITTED"); // không phải DONE
		assertThat((String) at(sub, "$.task.timeliness")).isEqualTo("ON_TIME_PENDING_REVIEW");
		UUID subId = uuidAt(sub, "$.submission.id");
		// trước giờ chốt: xem trước nhưng chưa duyệt được
		MvcResult early = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.lead(), 200);
		assertThat((Boolean) at(early, "$.canReview")).isFalse();
		assertThat((String) at(early, "$.cannotReviewReason.code")).isEqualTo("REVIEW_BEFORE_CUTOFF");
		assertThat(code(POST(reviewUrl(subId), w.lead(), accept(), 409))).isEqualTo("REVIEW_BEFORE_CUTOFF");
		// TL duyệt hôm sau (thứ Bảy 21/11 09:00 VN) -> vẫn đúng hạn
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-21T02:00:00Z"));
		MvcResult pre = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.lead(), 200);
		assertThat((Boolean) at(pre, "$.canReview")).isTrue();
		assertThat((Object) at(pre, "$.awardPreview")).isNull(); // chưa có phương án điểm
		MvcResult r = POST(reviewUrl(subId), w.lead(), accept(), 200);
		assertThat((String) at(r, "$.review.decision")).isEqualTo("ACCEPTED");
		assertThat((String) at(r, "$.task.taskStatus")).isEqualTo("DONE");
		assertThat((String) at(r, "$.task.timeliness")).isEqualTo("ON_TIME_ACCEPTED");
		assertThat((Boolean) at(r, "$.task.isLate")).isFalse();
		assertThat((Object) at(r, "$.award")).isNull();
		assertThat(hook.calls).hasSize(1);
		assertThat(hook.calls.getFirst().submittedAt()).isEqualTo(AT_1930); // submitted_at của bản được chấp nhận
		assertThat(hook.calls.getFirst().reviewedAt()).isEqualTo(Instant.parse("2026-11-21T02:00:00Z"));
		assertThat(hook.calls.getFirst().submittedBy()).isEqualTo(w.a().id());
		MvcResult detail = GET("/api/v1/tasks/" + t.taskId(), w.a(), 200);
		assertThat((String) at(detail, "$.completedAt")).isEqualTo(AT_1930.toString());
		assertThat((String) at(detail, "$.acceptedAt")).isEqualTo("2026-11-21T02:00:00Z");
		assertThat((Boolean) at(detail, "$.completedLate")).isFalse();
		assertThat(notes.of("TASK_REVIEWED").getFirst().recipientIds()).containsExactly(w.a().id());
		// duyệt lặp: cùng quyết định -> kết quả hiện có, không gọi hook lần hai, không thêm bản ghi
		MvcResult again = POST(reviewUrl(subId), w.lead(), accept(), 200);
		assertThat((String) at(again, "$.review.id")).isEqualTo((String) at(r, "$.review.id"));
		assertThat(hook.calls).hasSize(1);
		assertThat(jdbc.sql("SELECT count(*) FROM task_result_reviews WHERE submission_id = :s").param("s", subId).query(Long.class).single()).isEqualTo(1L);
		assertThat(code(POST(reviewUrl(subId), w.lead(), body("decision", "FIX_REQUIRED", "issueDescription", "Lỗi API trả sai mã"), 409)))
				.isEqualTo("SUBMISSION_ALREADY_REVIEWED");
		// kỳ đã chốt hết -> FINALIZED
		assertThat(jdbc.sql("SELECT status FROM checkpoint_cycles WHERE id = :c").param("c", w.c1().id()).query(String.class).single()).isEqualTo("FINALIZED");
	}

	@Test
	void lateSubmissionAndSubmissionWindow() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Nộp trễ", "EASY", FRI_DEADLINE);
		// 21:00 VN: sau deadline 20:00 nhưng trước cutoff 22:00 -> được nộp, tính trễ
		clock.set(Instant.parse("2026-11-20T14:00:00Z"));
		MvcResult s1 = submitResult(t, w.a(), 201);
		assertThat((Boolean) at(s1, "$.submission.isOnTime")).isFalse();
		assertThat((String) at(s1, "$.task.timeliness")).isEqualTo("LATE_PENDING_REVIEW");
		assertThat((Boolean) at(s1, "$.task.isLate")).isTrue();
		// nộp lại đúng giờ chốt (<=) vẫn được; bản mới nhất là bản TL xét
		clock.set(w.c1().closesAt());
		MvcResult s2 = submitResult(t, w.a(), 201);
		assertThat((Integer) at(s2, "$.submission.submissionNo")).isEqualTo(2);
		assertThat((Boolean) at(GET("/api/v1/task-rounds/" + t.roundId() + "/submissions", w.b(), 200), "$.items[0].isReviewTarget")).isFalse();
		// quá cutoff 1 giây -> bị khóa
		clock.set(w.c1().closesAt().plusSeconds(1));
		assertThat(code(submitResult(t, w.a(), 409))).isEqualTo("SUBMISSION_WINDOW_CLOSED");
		// kiểm tra đầu vào & quyền
		assertThat(code(POST("/api/v1/task-rounds/" + t.roundId() + "/submissions", w.b(), "{\"planRevisionId\":\"" + t.planId()
				+ "\",\"evidence\":" + HTTPS_EVIDENCE + "}", 403))).isEqualTo("NOT_ASSIGNEE");
		clock.set(NOW);
		assertThat(code(POST("/api/v1/task-rounds/" + t.roundId() + "/submissions", w.a(), "{\"planRevisionId\":\"" + t.planId()
				+ "\",\"evidence\":[]}", 422))).isEqualTo("EVIDENCE_REQUIRED");
		POST("/api/v1/task-rounds/" + t.roundId() + "/submissions", w.a(), "{\"planRevisionId\":\"" + t.planId()
				+ "\",\"evidence\":[{\"type\":\"URL\",\"url\":\"http://khong-https\"}]}", 400);
		assertThat(code(POST("/api/v1/task-rounds/" + t.roundId() + "/submissions", w.a(), "{\"planRevisionId\":\"" + UUID.randomUUID()
				+ "\",\"evidence\":" + HTTPS_EVIDENCE + "}", 409))).isEqualTo("PLAN_CHANGED");
	}

	@Test
	void fixRequiredThenCarryoverToNextCycleSameTask() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Cần sửa", "MEDIUM", FRI_DEADLINE);
		clock.set(AT_1930);
		UUID subId = uuidAt(submitResult(t, w.a(), 201), "$.submission.id");
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-20T16:00:00Z"));
		assertThat(code(POST(reviewUrl(subId), w.lead(), body("decision", "FIX_REQUIRED"), 400))).isEqualTo("VALIDATION_FAILED");
		POST(reviewUrl(subId), w.lead(), body("decision", "FIX_REQUIRED", "issueDescription", "ngắn"), 400);
		MvcResult r = POST(reviewUrl(subId), w.lead(), body("decision", "FIX_REQUIRED", "issueDescription", "Lỗi API trả sai mã 500"), 200);
		assertThat((String) at(r, "$.task.taskStatus")).isEqualTo("FIX_REQUIRED");
		assertThat((String) at(r, "$.task.timeliness")).isEqualTo("NEEDS_REWORK");
		assertThat((Boolean) at(r, "$.task.isLate")).isTrue();
		assertThat((Boolean) at(r, "$.task.needsCarryover")).isTrue();
		assertThat(hook.calls).isEmpty(); // không ghi điểm
		assertThat(notes.of("TASK_REVIEWED").getFirst().message()).contains("Lỗi API trả sai mã 500");
		assertThat(jdbc.sql("SELECT state FROM task_review_rounds WHERE id = :r").param("r", t.roundId()).query(String.class).single())
				.isEqualTo("CARRYOVER_REQUIRED");
		// hàng đợi duyệt: task nằm ở carryoverRequired
		MvcResult q = GET("/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/review-queue", w.lead(), 200);
		assertThat((Integer) at(q, "$.carryoverRequired.length()")).isEqualTo(1);
		assertThat((Boolean) at(q, "$.carryoverRequired[0].row.carryover.required")).isTrue();
		GET("/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/review-queue", w.b(), 403);
		// chuyển kỳ: nhiều ràng buộc
		String url = "/api/v1/task-rounds/" + t.roundId() + "/carryover";
		POST(url, w.a(), body("targetCycleId", w.c2().id(), "deadline", "2026-11-27T13:00:00Z"), 403);
		assertThat(code(POST(url, w.lead(), body("targetCycleId", w.c2().id(), "deadline", "2026-11-20T10:00:00Z"), 422))).isEqualTo("CARRYOVER_DEADLINE_INVALID");
		assertThat(code(POST(url, w.lead(), body("targetCycleId", w.c2().id(), "deadline", "2026-12-05T13:00:00Z"), 422))).isEqualTo("CARRYOVER_DEADLINE_INVALID");
		assertThat(code(POST(url, w.lead(), body("targetCycleId", w.cycles().get(2).id(), "deadline", "2026-12-03T13:00:00Z"), 422))).isEqualTo("CARRYOVER_REASON_REQUIRED");
		assertThat(code(POST(url, w.lead(), body("targetCycleId", w.c1().id(), "deadline", "2026-11-27T13:00:00Z"), 422))).isEqualTo("CARRYOVER_DEADLINE_INVALID");
		MvcResult co = POST(url, w.lead(), body("targetCycleId", w.c2().id(), "deadline", "2026-11-27T13:00:00Z", "scopeNote", "Sửa lỗi mã 500"), 201);
		assertThat((String) at(co, "$.sourceRound.state")).isEqualTo("CARRIED_OVER");
		assertThat((String) at(co, "$.targetRound.state")).isEqualTo("ACTIVE");
		assertThat((Integer) at(co, "$.targetRound.roundNo")).isEqualTo(2);
		assertThat((String) at(co, "$.targetRound.reason")).isEqualTo("FIX_REQUIRED");
		assertThat((String) at(co, "$.targetRound.previousRoundId")).isEqualTo(t.roundId().toString());
		assertThat((String) at(co, "$.plan.approvalSource")).isEqualTo("TL_CARRYOVER");
		assertThat((String) at(co, "$.plan.deadline")).isEqualTo("2026-11-27T13:00:00Z");
		assertThat((String) at(co, "$.plan.description")).isEqualTo("Sửa lỗi mã 500");
		assertThat((String) at(co, "$.task.id")).isEqualTo(t.taskId().toString()); // cùng task_id
		assertThat((String) at(co, "$.task.taskStatus")).isEqualTo("IN_PROGRESS");
		assertThat((String) at(co, "$.task.deadline")).isEqualTo("2026-11-27T13:00:00Z");
		assertThat((String) at(co, "$.task.originalDeadline")).isEqualTo(FRI_DEADLINE.toString()); // giữ deadline gốc
		assertThat((Integer) at(co, "$.task.cycle.sequenceNo")).isEqualTo(2);
		assertThat(notes.of("TASK_CARRIED_OVER").getFirst().recipientIds()).containsExactly(w.a().id());
		// vòng cũ giữ nguyên: bản nộp, quyết định và kết quả của kỳ 20/11
		MvcResult d = GET("/api/v1/tasks/" + t.taskId(), w.a(), 200);
		assertThat((Integer) at(d, "$.rounds.length()")).isEqualTo(2);
		assertThat((String) at(d, "$.rounds[0].decision")).isEqualTo("FIX_REQUIRED");
		assertThat((String) at(d, "$.rounds[0].carryover.targetRoundId")).isEqualTo((String) at(co, "$.targetRound.id"));
		assertThat((Integer) at(d, "$.submissions.length()")).isEqualTo(0); // vòng mới chưa nộp
		assertThat(jdbc.sql("SELECT count(*) FROM task_submissions WHERE round_id = :r").param("r", t.roundId()).query(Long.class).single()).isEqualTo(1L);
		assertThat(jdbc.sql("SELECT status FROM checkpoint_cycles WHERE id = :c").param("c", w.c1().id()).query(String.class).single()).isEqualTo("FINALIZED");
		// lặp lại cùng tham số -> 200 replay, khác tham số -> 409
		MvcResult replay = POST(url, w.lead(), body("targetCycleId", w.c2().id(), "deadline", "2026-11-27T13:00:00Z"), 200);
		assertThat((String) at(replay, "$.targetRound.id")).isEqualTo((String) at(co, "$.targetRound.id"));
		assertThat(code(POST(url, w.lead(), body("targetCycleId", w.c2().id(), "deadline", "2026-11-26T13:00:00Z"), 409))).isEqualTo("CARRYOVER_ALREADY_DONE");
		assertThat(jdbc.sql("SELECT count(*) FROM task_review_rounds WHERE task_id = :t").param("t", t.taskId()).query(Long.class).single()).isEqualTo(2L);
		// vòng mới: nộp lại và đánh giá theo deadline xử lý tiếp (không đổi kết quả vòng cũ)
		clock.set(Instant.parse("2026-11-26T03:00:00Z"));
		MvcResult s2 = POST("/api/v1/task-rounds/" + at(co, "$.targetRound.id") + "/submissions", w.a(),
				"{\"planRevisionId\":\"" + at(co, "$.plan.id") + "\",\"evidence\":" + HTTPS_EVIDENCE + "}", 201);
		assertThat((String) at(s2, "$.task.timeliness")).isEqualTo("ON_TIME_PENDING_REVIEW");
		assertThat((Integer) at(s2, "$.task.roundNo")).isEqualTo(2);
	}

	@Test
	void taskWaitingForReviewIsNotCarriedOverAndNoResultTaskIs() throws Exception {
		World w = world();
		Task pending = approvedTask(w, w.a(), "Chờ duyệt", "EASY", FRI_DEADLINE);
		Task none = approvedTask(w, w.b(), "Chưa nộp", "EASY", FRI_DEADLINE);
		Task early = approvedTask(w, w.c(), "Sớm", "EASY", FRI_DEADLINE);
		clock.set(AT_1930);
		submitResult(pending, w.a(), 201);
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-22T03:00:00Z")); // TL chậm 1,5 ngày
		MvcResult d = GET("/api/v1/tasks/" + pending.taskId(), w.a(), 200);
		assertThat((String) at(d, "$.taskStatus")).isEqualTo("SUBMITTED"); // không tự chuyển kỳ, không đổi trạng thái
		assertThat((String) at(d, "$.timeliness")).isEqualTo("ON_TIME_PENDING_REVIEW");
		assertThat((Boolean) at(d, "$.isLate")).isFalse(); // TL duyệt chậm không làm trễ
		assertThat((Boolean) at(d, "$.needsCarryover")).isFalse();
		assertThat((Integer) at(d, "$.cycle.sequenceNo")).isEqualTo(1);
		assertThat(code(POST("/api/v1/task-rounds/" + pending.roundId() + "/carryover", w.lead(),
				body("targetCycleId", w.c2().id(), "deadline", "2026-11-27T13:00:00Z"), 409))).isEqualTo("CARRYOVER_AWAITING_REVIEW");
		// task chưa nộp: quá hạn, cần chuyển kỳ, lý do NOT_SUBMITTED
		MvcResult n = GET("/api/v1/tasks/" + none.taskId(), w.b(), 200);
		assertThat((String) at(n, "$.timeliness")).isEqualTo("OVERDUE_NO_RESULT");
		assertThat((Boolean) at(n, "$.isLate")).isTrue();
		assertThat((String) at(n, "$.carryoverReason")).isEqualTo("NOT_SUBMITTED");
		assertThat((java.util.List<String>) at(GET("/api/v1/tasks/" + none.taskId(), w.lead(), 200), "$.allowedActions")).contains("SET_CARRYOVER");
		MvcResult q = GET("/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/review-queue", w.lead(), 200);
		assertThat((Integer) at(q, "$.awaitingReview.length()")).isEqualTo(1);
		assertThat((Boolean) at(q, "$.awaitingReview[0].canReviewNow")).isTrue();
		assertThat((Integer) at(q, "$.carryoverRequired.length()")).isEqualTo(2); // chưa nộp: none + early
		MvcResult co = POST("/api/v1/task-rounds/" + none.roundId() + "/carryover", w.lead(),
				body("targetCycleId", w.c2().id(), "deadline", "2026-11-27T13:00:00Z"), 201);
		assertThat((String) at(co, "$.targetRound.reason")).isEqualTo("NOT_SUBMITTED");
		// kỳ chưa FINALIZED vì còn task chờ duyệt
		assertThat(jdbc.sql("SELECT status FROM checkpoint_cycles WHERE id = :c").param("c", w.c1().id()).query(String.class).single()).isEqualTo("CLOSED");
		// chuyển kỳ trước giờ chốt bị chặn
		clock.set(NOW);
		assertThat(code(POST("/api/v1/task-rounds/" + early.roundId() + "/carryover", w.lead(),
				body("targetCycleId", w.c2().id(), "deadline", "2026-11-27T13:00:00Z"), 409))).isEqualTo("CARRYOVER_NOT_ALLOWED");
	}

	@Test
	void onlyTheRightReviewerCanReviewAndOutsidersCannot() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Quyền duyệt", "EASY", FRI_DEADLINE);
		clock.set(AT_1930);
		UUID subId = uuidAt(submitResult(t, w.a(), 201), "$.submission.id");
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-21T02:00:00Z"));
		assertThat(code(POST(reviewUrl(subId), w.b(), accept(), 403))).isEqualTo("NOT_AUTHORIZED_REVIEWER");
		assertThat(code(POST(reviewUrl(subId), w.a(), accept(), 403))).isEqualTo("NOT_AUTHORIZED_REVIEWER");
		DbFixtures.GroupRef other = fx.group("Nhóm khác", 3);
		assertThat(code(POST(reviewUrl(subId), other.leader(), accept(), 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		GET("/api/v1/task-submissions/" + subId + "/review-preview", other.leader(), 404);
		MvcResult pre = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.b(), 200);
		assertThat((Boolean) at(pre, "$.canReview")).isFalse();
		assertThat((String) at(pre, "$.cannotReviewReason.code")).isEqualTo("NOT_AUTHORIZED_REVIEWER");
		assertThat(jdbc.sql("SELECT count(*) FROM task_result_reviews WHERE submission_id = :s").param("s", subId).query(Long.class).single()).isEqualTo(0L);
		POST(reviewUrl(subId), w.lead(), body("decision", "MAYBE"), 400);
	}

	/** Dựng quỹ điểm tối thiểu: mỗi người 200.000 units; mỗi (task, chủ, units) có assignment CURRENT cho chủ. Policy ACTIVE. */
	private void points(World w, Object[]... specs) {
		UUID policy = fx.pointPolicyDraft(w.pid(), w.lead().id(), 5);
		Map<UUID, Long> used = new HashMap<>();
		for (Object[] s : specs) {
			used.merge(((UserRef) s[1]).id(), (Long) s[2], Long::sum);
		}
		Map<UUID, UUID> budget = new HashMap<>();
		for (UserRef u : w.g().all()) {
			budget.put(u.id(), fx.memberBudget(policy, w.pid(), u.id(), 200_000, 200_000 - used.getOrDefault(u.id(), 0L), 6));
		}
		for (Object[] s : specs) {
			Task t = (Task) s[0];
			UserRef owner = (UserRef) s[1];
			long units = (Long) s[2];
			TaskRef ref = new TaskRef(t.taskId(), t.roundId(), t.planId(), owner.id(), w.c1().id(), FRI_DEADLINE);
			UUID b = fx.taskBudget(w.pid(), budget.get(owner.id()), ref, "HARD", 3, units);
			fx.assignment(w.pid(), b, owner.id(), w.lead().id());
		}
		fx.activatePolicy(policy, w.lead().id());
	}

	@Test
	void pointsPreviewAwardInResponseAndHookFailureRollsBack() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Có điểm", "HARD", FRI_DEADLINE);
		points(w, new Object[] { t, w.a(), 100_000L });
		clock.set(AT_1930);
		UUID subId = uuidAt(submitResult(t, w.a(), 201), "$.submission.id");
		assertThat((Object) at(GET("/api/v1/tasks/" + t.taskId(), w.b(), 200), "$.points.amount.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(GET("/api/v1/tasks/" + t.taskId(), w.b(), 200), "$.points.status")).isEqualTo("AWAITING_REVIEW");
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-21T02:00:00Z"));
		MvcResult pre = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.lead(), 200);
		assertThat((String) at(pre, "$.awardPreview.points.percentLabel")).isEqualTo("+10,0000");
		assertThat((String) at(pre, "$.awardPreview.confirmedBefore.percentLabel")).isEqualTo("0,0000");
		assertThat((String) at(pre, "$.awardPreview.confirmedAfter.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(pre, "$.awardPreview.notice")).isEqualTo("Điểm chỉ được ghi sau khi kết quả được chấp nhận");
		// hook lỗi giữa chừng -> rollback toàn bộ
		hook.writePoints = false; // hook thật của BE-B2 (points) ghi AWARD sau khi gộp
		hook.failNext = true;
		MvcResult fail = POST(reviewUrl(subId), w.lead(), accept(), 500);
		assertThat(code(fail)).isEqualTo("INTERNAL_ERROR");
		assertThat(jdbc.sql("SELECT count(*) FROM task_result_reviews WHERE submission_id = :s").param("s", subId).query(Long.class).single()).isEqualTo(0L);
		assertThat(jdbc.sql("SELECT state FROM task_review_rounds WHERE id = :r").param("r", t.roundId()).query(String.class).single()).isEqualTo("AWAITING_REVIEW");
		// thành công: đúng một khoản AWARD
		MvcResult ok = POST(reviewUrl(subId), w.lead(), accept(), 200);
		assertThat((String) at(ok, "$.award.points.percentLabel")).isEqualTo("+10,0000");
		assertThat((String) at(ok, "$.award.confirmedAfter.percentLabel")).isEqualTo("10,0000");
		assertThat((String) at(ok, "$.award.source")).isEqualTo("OWN");
		assertThat((Boolean) at(ok, "$.task.points.awarded")).isTrue();
		assertThat((String) at(ok, "$.task.points.status")).isEqualTo("AWARDED");
		POST(reviewUrl(subId), w.lead(), accept(), 200); // lặp
		assertThat(jdbc.sql("SELECT count(*) FROM point_ledger_entries WHERE project_id = :p").param("p", w.pid()).query(Long.class).single()).isEqualTo(1L);
		// xem lại sau khi đã duyệt: "trước" trừ khoản đã ghi, không đếm đôi (before + 10 = after)
		MvcResult post = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.lead(), 200);
		assertThat((String) at(post, "$.awardPreview.confirmedBefore.percentLabel")).isEqualTo("0,0000");
		assertThat((String) at(post, "$.awardPreview.confirmedAfter.percentLabel")).isEqualTo("10,0000");
	}

	@Test
	void leadsOwnTaskWithPointsNeedsIndependentReviewer() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.lead(), "Việc của TL", "HARD", FRI_DEADLINE);
		points(w, new Object[] { t, w.lead(), 100_000L });
		clock.set(AT_1930);
		UUID subId = uuidAt(submitResult(t, w.lead(), 201), "$.submission.id");
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-21T02:00:00Z"));
		assertThat(code(POST(reviewUrl(subId), w.lead(), accept(), 409))).isEqualTo("INDEPENDENT_REVIEWER_REQUIRED");
		assertThat(code(POST(reviewUrl(subId), w.b(), accept(), 403))).isEqualTo("NOT_AUTHORIZED_REVIEWER");
		PUT("/api/v1/tasks/" + t.taskId() + "/independent-reviewer", w.lead(), body("reviewerUserId", w.b().id()), 200);
		assertThat(code(POST(reviewUrl(subId), w.lead(), accept(), 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		assertThat(code(POST(reviewUrl(subId), w.c(), accept(), 403))).isEqualTo("NOT_AUTHORIZED_REVIEWER");
		MvcResult pre = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.b(), 200);
		assertThat((Boolean) at(pre, "$.canReview")).isTrue();
		assertThat((String) at(pre, "$.authorizedReviewer.name")).isEqualTo(w.b().name());
		MvcResult ok = POST(reviewUrl(subId), w.b(), accept(), 200);
		assertThat((String) at(ok, "$.review.reviewer.name")).isEqualTo(w.b().name());
		assertThat((String) at(ok, "$.task.taskStatus")).isEqualTo("DONE");
	}

	@Test
	void queueListsReviewedTasksAndMemberSeesNothingOfLeadActions() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Đã duyệt", "EASY", FRI_DEADLINE);
		clock.set(AT_1930);
		UUID subId = uuidAt(submitResult(t, w.a(), 201), "$.submission.id");
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-21T02:00:00Z"));
		String qUrl = "/api/v1/groups/" + w.pid() + "/checkpoint-cycles/" + w.c1().id() + "/review-queue";
		assertThat((Integer) at(GET(qUrl, w.lead(), 200), "$.awaitingReview.length()")).isEqualTo(1);
		POST(reviewUrl(subId), w.lead(), accept(), 200);
		MvcResult q = GET(qUrl, w.lead(), 200);
		assertThat((Integer) at(q, "$.awaitingReview.length()")).isEqualTo(0);
		assertThat((Integer) at(q, "$.reviewed.length()")).isEqualTo(1);
		assertThat((String) at(q, "$.reviewed[0].row.timeliness")).isEqualTo("ON_TIME_ACCEPTED");
		assertThat((String) at(q, "$.cycle.phase")).isEqualTo("FINALIZED");
		assertThat(List.of(w.lead())).isNotEmpty();
	}

	@Test
	void openHelpRequestBlocksAcceptingUntilClosed() throws Exception {
		World w = world();
		Task t = approvedTask(w, w.a(), "Có yêu cầu hỗ trợ", "EASY", FRI_DEADLINE);
		clock.set(AT_1930);
		UUID subId = uuidAt(submitResult(t, w.a(), 201), "$.submission.id");
		closeCycle(w, w.c1());
		clock.set(Instant.parse("2026-11-21T02:00:00Z"));
		UUID help = jdbc.sql("""
				INSERT INTO help_requests (project_id, task_id, beneficiary_id, result_approver_id, created_by, title, description,
				    expected_output, difficulty, deadline)
				VALUES (:p, :t, :a, :l, :l, 'Cần người làm thay', 'Mô tả', 'Đầu ra', 'EASY', :dl) RETURNING id
				""").param("p", w.pid()).param("t", t.taskId()).param("a", w.a().id()).param("l", w.lead().id())
				.param("dl", app.hanare.common.DbTime.toDb(FRI_DEADLINE)).query(UUID.class).single();
		assertThat((Boolean) at(GET("/api/v1/tasks/" + t.taskId(), w.lead(), 200), "$.hasOpenHelpRequest")).isTrue();
		assertThat(code(POST(reviewUrl(subId), w.lead(), accept(), 409))).isEqualTo("HELP_REQUEST_BLOCKS_REVIEW");
		MvcResult pre = GET("/api/v1/task-submissions/" + subId + "/review-preview", w.lead(), 200);
		assertThat((String) at(pre, "$.cannotReviewReason.code")).isEqualTo("HELP_REQUEST_BLOCKS_REVIEW");
		jdbc.sql("UPDATE help_requests SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = 'Đóng' WHERE id = :h").param("h", help).update();
		POST(reviewUrl(subId), w.lead(), accept(), 200);
	}
}
