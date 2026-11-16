package app.hanare.help;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.help.HelpDtos.EvidenceInput;
import app.hanare.help.HelpDtos.ReportUnableRequest;
import app.hanare.help.HelpDtos.ResolveDiscussionRequest;
import app.hanare.help.HelpDtos.SubmitSupportResultRequest;
import app.hanare.help.HelpDtos.SubmitSupportResultResponse;
import app.hanare.help.HelpDtos.SupportSubmissionDto;
import app.hanare.help.HelpDtos.SupportWorkDto;
import app.hanare.help.HelpDtos.SupportWorkResolution;
import app.hanare.help.HelpViews.Req;
import app.hanare.help.HelpViews.SubmitCheck;
import app.hanare.help.HelpViews.Work;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.AuditWriter;
import app.hanare.points.Err;
import app.hanare.points.EvidenceViews;
import app.hanare.points.IdempotencyKey;
import app.hanare.points.Lookups;
import app.hanare.points.PointDtos.EvidenceItem;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.BudgetFacts;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phần việc của Helper (S10/S11): xem, nộp kết quả (tạo cả {@code TaskSubmission} của task gốc), báo không thể hoàn thành,
 * TL giải quyết bất đồng. Không ghi ledger ở đây — chỉ {@link AcceptedSubmissionHandler} (khi TL duyệt) và đảo khoản.
 */
@Service
public class SupportWorkService {

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final HelpViews views;
	private final EvidenceViews evidence;
	private final HandoffService handoff;
	private final HelpNotices notices;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public SupportWorkService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, HelpViews views,
			EvidenceViews evidence, HandoffService handoff, HelpNotices notices, AuditWriter audit,
			NotificationService notifications, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.views = views;
		this.evidence = evidence;
		this.handoff = handoff;
		this.notices = notices;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------------ xem

	@Transactional
	public SupportWorkDto get(UUID workId, CurrentUser user) {
		Work w0 = views.work(workId);
		access.requireMember(w0.projectId(), user);
		Req r = views.req(w0.helpRequestId());
		boolean allowed = user.id().equals(w0.helperId()) || user.id().equals(r.createdBy())
				|| user.id().equals(r.beneficiaryId()) || user.id().equals(w0.approverId());
		if (!allowed) {
			throw ApiException.forbidden("Bạn không có quyền xem phần việc hỗ trợ này.");
		}
		syncWork(workId);
		return views.workDto(views.work(workId), user.id());
	}

	// ------------------------------------------------------------------------------------------------ nộp kết quả

	@Transactional
	public SubmitSupportResultResponse submit(UUID workId, CurrentUser user, String idempotencyKey,
			SubmitSupportResultRequest req) {
		IdempotencyKey.require(idempotencyKey);
		Work w0 = views.work(workId);
		access.requireMember(w0.projectId(), user);
		if (!user.id().equals(w0.helperId())) {
			throw ApiException.forbidden("Chỉ người được chọn hỗ trợ mới nộp được kết quả.");
		}
		List<EvidenceInput> input = req.evidence() == null ? List.of() : req.evidence();
		if (input.isEmpty()) {
			throw Err.unprocessable("EVIDENCE_REQUIRED", "Cần ít nhất một minh chứng (liên kết kết quả).");
		}
		if (input.size() > 5) {
			throw ApiException.validation("evidence", "Tối đa 5 minh chứng.");
		}
		List<EvidenceItem> items = new ArrayList<>();
		for (int i = 0; i < input.size(); i++) {
			String url = input.get(i).url() == null ? "" : input.get(i).url().trim();
			if (!(url.startsWith("https://") || url.startsWith("http://")) || url.length() > 2048) {
				throw ApiException.validation("evidence[" + i + "].url", "Minh chứng phải là liên kết http(s) hợp lệ.");
			}
			items.add(new EvidenceItem("URL", url, input.get(i).label()));
		}
		if (req.actualMinutes() != null && req.actualMinutes() <= 0) {
			throw ApiException.validation("actualMinutes", "Phải là số dương.");
		}
		syncWork(workId);
		Work w = views.workForUpdate(workId);
		Req r = views.req(w.helpRequestId());
		SubmitCheck check = views.submitCheck(w, r);
		if (!check.ok()) {
			throw Err.conflict(check.reason().code(), check.reason().message());
		}
		TaskFacts f = tasks.facts(w.projectId(), r.taskId()).orElseThrow();
		Instant now = clock.instant();
		int taskNo = jdbc.sql("SELECT COALESCE(MAX(submission_no), 0) + 1 FROM task_submissions WHERE round_id = :r")
				.param("r", f.roundId()).query(Integer.class).single();
		String evJson = evidence.toJson(items);
		UUID taskSubmissionId = jdbc.sql("""
				INSERT INTO task_submissions (project_id, round_id, plan_revision_id, submission_no, submitted_by, result_note,
				    evidence, submitted_at)
				VALUES (:p, :r, :plan, :n, :by, :note, CAST(:ev AS jsonb), :at) RETURNING id
				""").param("p", w.projectId()).param("r", f.roundId()).param("plan", f.effectivePlanId()).param("n", taskNo)
				.param("by", user.id()).param("note", req.resultNote().trim()).param("ev", evJson).param("at", DbTime.toDb(now))
				.query(UUID.class).single();
		jdbc.sql("UPDATE task_review_rounds SET state = 'AWAITING_REVIEW', version = version + 1 WHERE id = :r AND state = 'ACTIVE'")
				.param("r", f.roundId()).update();
		int revisionNo = jdbc.sql("SELECT COALESCE(MAX(revision_no), 0) + 1 FROM support_submissions WHERE work_id = :w")
				.param("w", workId).query(Integer.class).single();
		UUID submissionId = jdbc.sql("""
				INSERT INTO support_submissions (work_id, revision_no, result_note, evidence, actual_minutes, submitted_at,
				    task_submission_id)
				VALUES (:w, :n, :note, CAST(:ev AS jsonb), :min, :at, :ts) RETURNING id
				""").param("w", workId).param("n", revisionNo).param("note", req.resultNote().trim()).param("ev", evJson)
				.param("min", req.actualMinutes()).param("at", DbTime.toDb(now)).param("ts", taskSubmissionId)
				.query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO contributions (project_id, work_id, help_request_id, helper_id)
				VALUES (:p, :w, :h, :u) ON CONFLICT (work_id) DO NOTHING
				""").param("p", w.projectId()).param("w", workId).param("h", w.helpRequestId()).param("u", user.id()).update();
		jdbc.sql("UPDATE support_works SET status = 'SUBMITTED', updated_at = :at, version = version + 1 WHERE id = :id")
				.param("at", DbTime.toDb(now)).param("id", workId).update();
		jdbc.sql("UPDATE help_requests SET status = 'SUBMITTED', updated_at = :at, version = version + 1 WHERE id = :id")
				.param("at", DbTime.toDb(now)).param("id", w.helpRequestId()).update();
		event(w, "RESULT_SUBMITTED", user.id(), null, now);
		audit.log(w.projectId(), user.id(), "SUPPORT_WORK", workId, "SUPPORT_RESULT_SUBMITTED", null,
				Map.of("revisionNo", revisionNo, "taskSubmissionId", taskSubmissionId));
		notifications.publish(new NotificationEvent(w.projectId(), "SUPPORT_SUBMITTED", "SUPPORT_WORK", workId, null,
				"work:" + workId + ":submitted:" + revisionNo, List.of(w.approverId()), "Helper đã gửi kết quả",
				lookups.userName(user.id()) + " đã gửi kết quả cho \"" + r.title() + "\". Bạn có thể duyệt sau giờ chốt kỳ.",
				Map.of("workId", workId.toString(), "taskId", r.taskId().toString())));
		SupportSubmissionDto dto = views.submissions(views.work(workId)).stream().filter(s -> s.id().equals(submissionId))
				.findFirst().orElseThrow();
		return new SubmitSupportResultResponse(dto, views.workDto(views.work(workId), user.id()));
	}

	// ------------------------------------------------------------------------------------------------ không thể hoàn thành

	@Transactional
	public SupportWorkDto unable(UUID workId, CurrentUser user, ReportUnableRequest req) {
		Work w0 = views.work(workId);
		access.requireMember(w0.projectId(), user);
		if (!user.id().equals(w0.helperId())) {
			throw ApiException.forbidden("Chỉ người được chọn hỗ trợ mới báo được.");
		}
		syncWork(workId);
		Work w = views.workForUpdate(workId);
		if (!"IN_PROGRESS".equals(w.status()) && !"REVISION_REQUESTED".equals(w.status())) {
			throw Err.conflict("WORK_INVALID_STATE", "Phần việc không ở trạng thái cho phép báo không thể hoàn thành.");
		}
		if (w.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		Req r = views.req(w.helpRequestId());
		Instant now = clock.instant();
		jdbc.sql("""
				UPDATE support_works SET status = 'NEEDS_DISCUSSION', unable_reason = :reason, updated_at = :at,
				       version = version + 1 WHERE id = :id
				""").param("reason", req.reason().trim()).param("at", DbTime.toDb(now)).param("id", workId).update();
		event(w, "UNABLE_REPORTED", user.id(), req.reason().trim(), now);
		audit.log(w.projectId(), user.id(), "SUPPORT_WORK", workId, "SUPPORT_WORK_UNABLE", null, Map.of("status", "NEEDS_DISCUSSION"));
		notifications.publish(new NotificationEvent(w.projectId(), "SUPPORT_WORK_ISSUE", "SUPPORT_WORK", workId, null,
				"work:" + workId + ":unable:" + (w.version() + 1), List.of(r.createdBy()), "Helper cần trao đổi",
				lookups.userName(user.id()) + " báo chưa thể hoàn thành \"" + r.title() + "\". Vui lòng trao đổi và xử lý.",
				Map.of("workId", workId.toString(), "taskId", r.taskId().toString())));
		return views.workDto(views.work(workId), user.id());
	}

	// ------------------------------------------------------------------------------------------------ TL giải quyết

	@Transactional
	public SupportWorkDto resolve(UUID workId, CurrentUser user, String idempotencyKey, ResolveDiscussionRequest req) {
		Work w0 = views.work(workId);
		GroupMembership lead = access.requireLeader(w0.projectId(), user);
		IdempotencyKey.require(idempotencyKey);
		Work w = views.workForUpdate(workId);
		Req r = views.reqForUpdate(w.helpRequestId());
		if (!lead.userId().equals(r.createdBy())) {
			throw app.hanare.common.ApiException.of(app.hanare.common.ErrorCode.LEAD_ONLY, "Chỉ người đăng yêu cầu được giải quyết.");
		}
		// idempotent tự nhiên: đã giải quyết cùng cách
		if (!"NEEDS_DISCUSSION".equals(w.status()) || !"IN_PROGRESS".equals(r.status())) {
			boolean sameReopen = req.resolution() == SupportWorkResolution.REOPEN_REQUEST && w.successorId() != null;
			boolean sameCancel = req.resolution() == SupportWorkResolution.CANCEL_REQUEST && "CANCELLED".equals(r.status())
					&& w.successorId() == null && "NEEDS_DISCUSSION".equals(w.status());
			if (sameReopen || sameCancel) {
				return views.workDto(w, lead.userId());
			}
			throw Err.conflict("WORK_INVALID_STATE", "Phần việc không ở trạng thái cần thảo luận.");
		}
		if (w.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		Instant now = clock.instant();
		String note = req.note().trim();
		if (req.resolution() == SupportWorkResolution.RESUME) {
			jdbc.sql("UPDATE support_works SET status = 'IN_PROGRESS', updated_at = :at, version = version + 1 WHERE id = :id")
					.param("at", DbTime.toDb(now)).param("id", workId).update();
			event(w, "DISCUSSION_RESOLVED", lead.userId(), "Tiếp tục thực hiện: " + note, now);
			audit.log(w.projectId(), lead.userId(), "SUPPORT_WORK", workId, "SUPPORT_WORK_RESUMED", null, Map.of());
			return views.workDto(views.work(workId), lead.userId());
		}
		// CANCEL_REQUEST / REOPEN_REQUEST: hoàn trả người thực hiện + quyền nhận điểm cho người gốc
		TaskFacts f = tasks.facts(w.projectId(), r.taskId()).orElseThrow();
		jdbc.sql("""
				UPDATE help_requests SET status = 'CANCELLED', cancelled_at = :at, cancel_reason = :reason, updated_at = :at,
				       version = version + 1 WHERE id = :id
				""").param("at", DbTime.toDb(now)).param("reason", note).param("id", r.id()).update();
		jdbc.sql("UPDATE support_offers SET status = 'WITHDRAWN', decided_at = NULL, decided_by = NULL WHERE id = :id")
				.param("id", w.offerId()).update();
		Optional<BudgetFacts> budget = tasks.budget(r.taskId()).filter(b -> "FUNDED".equals(b.state()));
		boolean returned = false;
		if (budget.isPresent() && budget.get().recipientId().equals(w.helperId())) {
			handoff.swapAssignment(w.projectId(), budget.get(), r.beneficiaryId(), r.id(), workId, lead.userId(), now);
			returned = true;
		}
		if (f.hasEffectivePlan() && !"ACCEPTED".equals(f.roundState()) && w.helperId().equals(f.assigneeId())) {
			handoff.handoffRevision(f, r.beneficiaryId(), lead.userId(), now, "Hoàn trả người thực hiện: " + note);
		}
		UUID successor = null;
		if (req.resolution() == SupportWorkResolution.REOPEN_REQUEST) {
			TaskFacts nf = tasks.facts(w.projectId(), r.taskId()).orElseThrow();
			successor = jdbc.sql("""
					INSERT INTO help_requests (project_id, support_request_id, task_id, round_id, beneficiary_id,
					    result_approver_id, created_by, title, description, expected_output, difficulty, deadline,
					    effort_estimate_minutes, skill_note, task_point_budget_id, advertised_point_units, created_at, updated_at)
					SELECT project_id, support_request_id, task_id, :round, beneficiary_id, result_approver_id, created_by, title,
					       description, expected_output, difficulty, :dl, effort_estimate_minutes, skill_note,
					       task_point_budget_id, advertised_point_units, :at, :at
					  FROM help_requests WHERE id = :old RETURNING id
					""").param("round", nf.roundId()).param("dl", DbTime.toDb(nf.deadline())).param("at", DbTime.toDb(now))
					.param("old", r.id()).query(UUID.class).single();
			if (budget.isPresent()) {
				jdbc.sql("INSERT INTO task_point_reservations (budget_id, help_request_id, point_units, created_at, updated_at) VALUES (:b, :h, :u, :at, :at)")
						.param("b", budget.get().budgetId()).param("h", successor).param("u", budget.get().units())
						.param("at", DbTime.toDb(now)).update();
			}
			jdbc.sql("UPDATE support_works SET successor_help_request_id = :s WHERE id = :id").param("s", successor)
					.param("id", workId).update();
		}
		jdbc.sql("UPDATE support_works SET updated_at = :at, version = version + 1 WHERE id = :id").param("at", DbTime.toDb(now))
				.param("id", workId).update();
		event(w, "DISCUSSION_RESOLVED", lead.userId(),
				(req.resolution() == SupportWorkResolution.REOPEN_REQUEST ? "Mở lại yêu cầu: " : "Đóng yêu cầu: ") + note, now);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("resolution", req.resolution().name());
		after.put("successorHelpRequestId", successor);
		audit.log(w.projectId(), lead.userId(), "SUPPORT_WORK", workId, "SUPPORT_WORK_DISCUSSION_RESOLVED", null, after);
		if (returned) {
			notifications.publish(new NotificationEvent(w.projectId(), "POINT_ASSIGNMENT_CHANGED", "HELP_REQUEST", r.id(), null,
					"work:" + workId + ":assignment-returned", List.of(r.beneficiaryId(), w.helperId()),
					"Quyền nhận điểm đã được hoàn trả",
					"Quyền nhận điểm của task " + f.title() + " đã được hoàn trả cho người phụ trách gốc (đây là khả năng nhận, chưa phải điểm đã ghi).",
					Map.of("taskId", r.taskId().toString(), "helpRequestId", r.id().toString())));
		}
		if (successor != null) {
			notices.helpPublished(w.projectId(), successor, f.title(), r.difficulty(), r.units(), lead.userId());
		}
		return views.workDto(views.work(workId), lead.userId());
	}

	// ------------------------------------------------------------------------------------------------ đồng bộ yêu cầu sửa

	/** Đồng bộ mọi work SUBMITTED của nhóm với quyết định của TL (REVISION_REQUESTED). */
	@Transactional
	public void syncProject(UUID projectId) {
		List<UUID> ids = jdbc.sql("SELECT id FROM support_works WHERE project_id = :p AND status = 'SUBMITTED'")
				.param("p", projectId).query(UUID.class).list();
		for (UUID id : ids) {
			syncWork(id);
		}
	}

	/**
	 * Cổng cho nơi ghi quyết định duyệt (BE-B1) hoặc đọc lười: nếu bản nộp mới nhất của work đã bị TL yêu cầu
	 * {@code FIX_REQUIRED/UPDATE_REQUIRED} thì work → {@code REVISION_REQUESTED} (giữ contribution chưa xác nhận),
	 * yêu cầu → {@code IN_PROGRESS}, thông báo {@code REVISION_REQUESTED} cho Helper. Idempotent.
	 */
	@Transactional
	public void syncWork(UUID workId) {
		Optional<Work> locked = jdbc.sql(HelpViews.WORK_SELECT + " WHERE id = :id FOR UPDATE").param("id", workId)
				.query((rs, i) -> HelpViews.mapWork(rs)).optional();
		if (locked.isEmpty() || !"SUBMITTED".equals(locked.get().status())) {
			return;
		}
		Work w = locked.get();
		record Rev(UUID taskSubmissionId, String decision, String issue, Instant at, UUID reviewer) {
		}
		Optional<Rev> rev = jdbc.sql("""
				SELECT ss.task_submission_id, rv.decision, rv.issue_description, rv.reviewed_at, rv.reviewer_id
				  FROM support_submissions ss JOIN task_result_reviews rv ON rv.submission_id = ss.task_submission_id
				 WHERE ss.work_id = :w ORDER BY ss.revision_no DESC LIMIT 1
				""").param("w", workId)
				.query((rs, i) -> new Rev(rs.getObject("task_submission_id", UUID.class), rs.getString("decision"),
						rs.getString("issue_description"), DbTime.instant(rs, "reviewed_at"),
						rs.getObject("reviewer_id", UUID.class)))
				.optional();
		if (rev.isEmpty() || "ACCEPTED".equals(rev.get().decision())) {
			return;
		}
		Instant now = clock.instant();
		jdbc.sql("UPDATE support_works SET status = 'REVISION_REQUESTED', updated_at = :at, version = version + 1 WHERE id = :id")
				.param("at", DbTime.toDb(now)).param("id", workId).update();
		jdbc.sql("UPDATE help_requests SET status = 'IN_PROGRESS', updated_at = :at, version = version + 1 WHERE id = :id AND status = 'SUBMITTED'")
				.param("at", DbTime.toDb(now)).param("id", w.helpRequestId()).update();
		event(w, "REVISION_REQUESTED", rev.get().reviewer(), rev.get().issue(), rev.get().at());
		Req r = views.req(w.helpRequestId());
		notifications.publish(new NotificationEvent(w.projectId(), "REVISION_REQUESTED", "SUPPORT_WORK", workId, null,
				"work:" + workId + ":revision:" + rev.get().taskSubmissionId(), List.of(w.helperId()),
				"TL yêu cầu bổ sung kết quả", "TL cần bạn sửa hoặc cập nhật kết quả cho \"" + r.title() + "\".",
				Map.of("workId", workId.toString(), "taskId", r.taskId().toString())));
	}

	/** Cổng cho BE-B1: gọi sau khi ghi quyết định FIX_REQUIRED/UPDATE_REQUIRED trên một lần nộp (tùy chọn; đọc lười cũng bắt được). */
	@Transactional
	public void onTaskSubmissionReviewed(UUID taskSubmissionId) {
		jdbc.sql("SELECT work_id FROM support_submissions WHERE task_submission_id = :t").param("t", taskSubmissionId)
				.query(UUID.class).optional().ifPresent(this::syncWork);
	}

	void event(Work w, String kind, UUID actor, String note, Instant at) {
		jdbc.sql("""
				INSERT INTO support_work_events (project_id, work_id, kind, actor_id, note, occurred_at)
				VALUES (:p, :w, :k, :a, :n, :at)
				""").param("p", w.projectId()).param("w", w.id()).param("k", kind).param("a", actor).param("n", note)
				.param("at", DbTime.toDb(at)).update();
	}
}
