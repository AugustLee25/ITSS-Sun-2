package app.hanare.task;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.help.SupportWorkService;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.task.CycleData.Reports;
import app.hanare.task.CycleData.Row;
import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Eval;
import app.hanare.task.TaskReader.Plan;
import app.hanare.task.TaskReader.Rev;
import app.hanare.task.TaskReader.Sub;
import app.hanare.task.TaskViews.Auth;
import app.hanare.task.TaskViews.Extras;
import app.hanare.task.TaskViews.Pt;
import app.hanare.task.TaskViews.Viewer;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mục G: nộp minh chứng, xem trước + duyệt kết quả (idempotent), chuyển kỳ (carryover), hàng đợi duyệt. */
@Service
public class ResultService {

	public record EvidenceInput(String type, String url, String label) {
	}

	public record SubmitResultRequest(UUID planRevisionId, String resultNote, List<EvidenceInput> evidence) {
	}

	public record ReviewResultRequest(String decision, String issueDescription) {
	}

	public record CarryoverRequest(UUID targetCycleId, Instant deadline, Instant startAt, String scopeNote, String decisionReason) {
	}

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Scope.Factory scopes;
	private final TaskReader reader;
	private final TaskViews views;
	private final CycleData data;
	private final CycleLifecycle lifecycle;
	private final Ops ops;
	private final ObjectProvider<TaskAcceptedHook> hooks;
	private final ObjectProvider<SupportWorkService> supportWorks;

	public ResultService(JdbcClient jdbc, GroupAccess access, Scope.Factory scopes, TaskReader reader, TaskViews views,
			CycleData data, CycleLifecycle lifecycle, Ops ops, ObjectProvider<TaskAcceptedHook> hooks,
			ObjectProvider<SupportWorkService> supportWorks) {
		this.supportWorks = supportWorks;
		this.jdbc = jdbc;
		this.access = access;
		this.scopes = scopes;
		this.reader = reader;
		this.views = views;
		this.data = data;
		this.lifecycle = lifecycle;
		this.ops = ops;
		this.hooks = hooks;
	}

	private Viewer viewer(GroupMembership m) {
		return new Viewer(m.userId(), m.role().name());
	}

	// ------------------------------------------------------------------ nộp minh chứng
	@Transactional
	public Map<String, Object> submit(UUID roundId, CurrentUser user, SubmitResultRequest r) {
		Ctx c0 = reader.load(roundId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy vòng xử lý."));
		UUID projectId = c0.round().projectId();
		GroupMembership m = access.requireMember(projectId, user);
		ops.lockRound(roundId);
		// khóa kỳ ở chế độ chia sẻ: job chốt (cập nhật dòng kỳ) và nộp bài tuần tự hóa; submittedAt lấy SAU khi có khóa
		Instant closesAt = jdbc.sql("SELECT closes_at FROM checkpoint_cycles WHERE id = :c FOR SHARE").param("c", c0.round().cycleId())
				.query((rs, i) -> J.inst(rs, "closes_at")).single();
		Ctx c = reader.require(roundId);
		Scope s = scopes.of(projectId);
		if (!user.id().equals(c.executor())) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		if (c.effective() == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "NO_EFFECTIVE_PLAN", "Task chưa có kế hoạch được duyệt.");
		}
		List<EvidenceInput> ev = validateEvidence(r);
		if (r.resultNote() != null && r.resultNote().length() > 2000) {
			throw ApiException.validation("resultNote", "Tối đa 2000 ký tự");
		}
		if (!List.of("ACTIVE", "AWAITING_REVIEW").contains(c.round().state())) {
			throw ApiException.of(HttpStatus.CONFLICT, "ROUND_NOT_ACTIVE", "Vòng xử lý không còn nhận kết quả.");
		}
		if (!c.effective().id().equals(r.planRevisionId())) {
			throw ApiException.of(HttpStatus.CONFLICT, "PLAN_CHANGED", "Kế hoạch đã thay đổi, hãy tải lại trước khi nộp.");
		}
		Instant now = ops.now();
		if (now.isAfter(closesAt)) {
			throw ApiException.of(HttpStatus.CONFLICT, "SUBMISSION_WINDOW_CLOSED", "Đã quá giờ chốt của kỳ — chờ TL chốt vòng xử lý tiếp.");
		}
		List<Map<String, Object>> evJson = ev.stream().map(e -> J.o("type", "URL", "url", e.url().strip(), "label",
				e.label() == null || e.label().isBlank() ? null : e.label().strip())).toList();
		UUID id = jdbc.sql("""
				INSERT INTO task_submissions (project_id, round_id, plan_revision_id, submission_no, submitted_by, result_note,
				    evidence, submitted_at, created_at)
				VALUES (:p, :r, :plan, (SELECT COALESCE(MAX(submission_no), 0) + 1 FROM task_submissions WHERE round_id = :r), :u,
				    :note, CAST(:ev AS jsonb), :at, :at) RETURNING id
				""").param("p", projectId).param("r", roundId).param("plan", c.effective().id()).param("u", user.id())
				.param("note", r.resultNote() == null || r.resultNote().isBlank() ? null : r.resultNote().strip())
				.param("ev", views.evidenceJson(evJson)).param("at", DbTime.toDb(now)).query(UUID.class).single();
		jdbc.sql("""
				UPDATE task_review_rounds SET state = 'AWAITING_REVIEW', work_started_at = COALESCE(work_started_at, :at),
				       version = version + 1 WHERE id = :r
				""").param("at", DbTime.toDb(now)).param("r", roundId).update();
		ops.bumpTask(c.round().taskId());
		Ctx fresh = reader.require(roundId);
		Sub sub = reader.submission(id).orElseThrow();
		boolean onTime = !sub.submittedAt().isAfter(sub.planDeadline());
		ops.history(projectId, user.id(), c.round().taskId(), "RESULT_SUBMITTED", c.round().roundNo(),
				s.name(user.id()) + " nộp kết quả lần " + sub.no() + (onTime ? " (đúng hạn)" : " (trễ hạn)"), id);
		return J.o("submission", views.submission(s, sub, c.round().taskId(), null, true), "task",
				views.summary(s, fresh, viewer(m), views.extras(List.of(fresh))));
	}

	private List<EvidenceInput> validateEvidence(SubmitResultRequest r) {
		List<EvidenceInput> ev = r.evidence();
		if (ev == null || ev.isEmpty()) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_REQUIRED", "Cần ít nhất một liên kết minh chứng.");
		}
		J.Check ck = J.check().require(ev.size() <= 5, "evidence", "Tối đa 5 minh chứng");
		for (int i = 0; i < ev.size(); i++) {
			EvidenceInput e = ev.get(i);
			String f = "evidence[" + i + "].url";
			if (e == null || e.url() == null || e.url().isBlank()) {
				ck.add(f, "Bắt buộc");
				continue;
			}
			if (e.type() != null && !"URL".equals(e.type())) {
				ck.add("evidence[" + i + "].type", "Chỉ hỗ trợ liên kết (URL)");
			}
			String u = e.url().strip();
			boolean ok = u.length() <= 2048 && u.startsWith("https://");
			if (ok) {
				try {
					ok = new URI(u).getHost() != null;
				}
				catch (Exception ex) {
					ok = false;
				}
			}
			ck.require(ok, f, "Phải là liên kết https hợp lệ (tối đa 2048 ký tự)");
			if (e.label() != null && e.label().length() > 200) {
				ck.add("evidence[" + i + "].label", "Tối đa 200 ký tự");
			}
		}
		ck.done();
		return ev;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> submissions(UUID roundId, CurrentUser user) {
		Ctx c = reader.load(roundId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy vòng xử lý."));
		access.requireMember(c.round().projectId(), user);
		Scope s = scopes.of(c.round().projectId());
		return J.o("items", views.submissions(s, c));
	}

	// ------------------------------------------------------------------ xem trước duyệt
	private record Target(Sub sub, Ctx c, Scope s, GroupMembership m, Pt pt, Extras x) {
	}

	private Target target(UUID submissionId, CurrentUser user) {
		Sub sub = reader.submission(submissionId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy lần nộp."));
		Ctx c0 = reader.require(sub.roundId());
		GroupMembership m = access.requireMember(c0.round().projectId(), user);
		return new Target(sub, c0, scopes.of(c0.round().projectId()), m, null, null);
	}

	private Target reload(Target t) {
		Ctx c = reader.require(t.sub().roundId());
		Extras x = views.extras(List.of(c));
		return new Target(reader.submission(t.sub().id()).orElseThrow(), c, t.s(), t.m(), x.points().get(c.round().taskId()), x);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> reviewPreview(UUID submissionId, CurrentUser user) {
		Target t = reload(target(submissionId, user));
		Scope s = t.s();
		Ctx c = t.c();
		CycleRow cy = s.cycle(c.round().cycleId());
		Viewer v = viewer(t.m());
		Rev rev = reader.reviewOf(submissionId).orElse(null);
		Map<String, Object> reason = null;
		Auth auth = TaskViews.reviewAuth(s, c, v, t.pt());
		boolean helpBlocks = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM help_requests WHERE task_id = :t AND status = 'OPEN')")
				.param("t", c.round().taskId()).query(Boolean.class).single());
		if (rev != null) {
			reason = reason("SUBMISSION_ALREADY_REVIEWED", "Lần nộp này đã có quyết định của TL.");
		}
		else if (c.sub() == null || !c.sub().id().equals(submissionId)) {
			reason = reason("STALE_SUBMISSION", "Đã có bản nộp mới hơn — hãy duyệt bản nộp chốt.");
		}
		else if (!s.now().isAfter(cy.closesAt())) {
			reason = reason("REVIEW_BEFORE_CUTOFF", "Chưa đến giờ chốt của kỳ — TL duyệt sau " + s.instantLabel(cy.closesAt()) + ".");
		}
		else if (auth == Auth.SELF) {
			reason = reason("SELF_ACTION_FORBIDDEN", "Không ai được duyệt khoản điểm mình nhận.");
		}
		else if (auth == Auth.INDEPENDENT_REQUIRED) {
			reason = reason("INDEPENDENT_REVIEWER_REQUIRED", "Task có điểm của TL cần người duyệt độc lập được chỉ định trước.");
		}
		else if (auth == Auth.NOT_AUTHORIZED) {
			reason = reason("NOT_AUTHORIZED_REVIEWER", "Bạn không phải người duyệt được chỉ định cho task này.");
		}
		else if (helpBlocks) {
			reason = reason("HELP_REQUEST_BLOCKS_REVIEW", "Còn yêu cầu hỗ trợ đang mở giữ quỹ điểm của task — đóng yêu cầu trước khi duyệt.");
		}
		UUID authorized = TaskViews.authorizedReviewer(s, c, t.pt());
		Map<String, Object> m = J.o("submission", views.submission(s, t.sub(), c.round().taskId(), rev,
				c.sub() != null && c.sub().id().equals(submissionId)),
				"task", views.summary(s, c, v, t.x()), "cycle", s.cycleRef(cy), "cutoffAt", cy.closesAt(),
				"canReview", reason == null, "cannotReviewReason", reason, "authorizedReviewer", s.user(authorized),
				"alreadyReviewed", rev != null, "awardPreview", awardPreview(s, c, t.pt(), rev), "supportWork", null);
		return m;
	}

	private static Map<String, Object> reason(String code, String message) {
		return J.o("code", code, "message", message);
	}

	private Map<String, Object> awardPreview(Scope s, Ctx c, Pt pt, Rev rev) {
		if (pt == null) {
			return null;
		}
		UUID recipient = pt.recipient() != null ? pt.recipient() : pt.origin();
		long before = confirmed(s.projectId(), recipient);
		if (rev != null && "ACCEPTED".equals(rev.decision())) {
			before = Math.max(0, before - pt.units()); // đã duyệt: ledger đã chứa khoản này, "trước" phải trừ ra để không đếm đôi
		}
		String title = c.display() == null ? "" : c.display().title();
		String pct = J.percentLabel(pt.units());
		boolean transferred = !recipient.equals(pt.origin());
		return J.o("recipient", s.user(recipient), "originalAssignee", s.user(pt.origin()), "taskTitle", title,
				"points", J.delta(pt.units()), "confirmedBefore", J.amount(before), "confirmedAfter", J.amount(before + pt.units()),
				"scoreLabel", "+" + pct + " điểm % cho " + s.name(recipient) + " từ task " + title + " của " + s.name(pt.origin()),
				"effectLabel", transferred ? s.name(pt.origin()) + " không nhận điểm task này; " + s.name(recipient) + " nhận toàn bộ quỹ của task"
						: s.name(recipient) + " nhận toàn bộ quỹ của task " + title,
				"notice", "Điểm chỉ được ghi sau khi kết quả được chấp nhận");
	}

	private long confirmed(UUID projectId, UUID member) {
		return jdbc.sql("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p AND recipient_id = :m")
				.param("p", projectId).param("m", member).query(Long.class).single();
	}

	// ------------------------------------------------------------------ duyệt kết quả
	@Transactional
	public Map<String, Object> review(UUID submissionId, CurrentUser user, ReviewResultRequest req) {
		Target t0 = target(submissionId, user);
		UUID projectId = t0.c().round().projectId();
		ops.lockRound(t0.c().round().id());
		Target t = reload(t0);
		Scope s = t.s();
		Ctx c = t.c();
		Viewer v = viewer(t.m());
		String decision = req.decision();
		J.Check ck = J.check().require(List.of("ACCEPTED", "FIX_REQUIRED", "UPDATE_REQUIRED").contains(decision), "decision",
				"Quyết định phải là ACCEPTED, FIX_REQUIRED hoặc UPDATE_REQUIRED");
		if (decision != null && !"ACCEPTED".equals(decision)) {
			ck.len(req.issueDescription(), "issueDescription", 10, 2000);
		}
		ck.done();
		Auth auth = TaskViews.reviewAuth(s, c, v, t.pt());
		switch (auth) {
			case SELF -> throw ApiException.of(ErrorCode.SELF_ACTION_FORBIDDEN, "Không ai được duyệt khoản điểm mình nhận.");
			case INDEPENDENT_REQUIRED -> throw ApiException.of(HttpStatus.CONFLICT, "INDEPENDENT_REVIEWER_REQUIRED",
					"Task có điểm của TL cần người duyệt độc lập được chỉ định trước.");
			case NOT_AUTHORIZED -> throw ApiException.of(ErrorCode.NOT_AUTHORIZED_REVIEWER, null);
			default -> {
			}
		}
		Rev existing = reader.reviewOf(submissionId).orElse(null);
		if (existing != null) {
			if (existing.decision().equals(decision)) {
				return reviewResponse(s, t, existing, v);
			}
			throw ApiException.of(HttpStatus.CONFLICT, "SUBMISSION_ALREADY_REVIEWED", "Lần nộp này đã có quyết định khác của TL.");
		}
		CycleRow cy = s.cycle(c.round().cycleId());
		Instant now = ops.now();
		if (!now.isAfter(cy.closesAt())) {
			throw ApiException.of(HttpStatus.CONFLICT, "REVIEW_BEFORE_CUTOFF", "Chưa đến giờ chốt của kỳ — TL duyệt sau giờ chốt.");
		}
		if (c.sub() == null || !c.sub().id().equals(submissionId)) {
			throw ApiException.of(HttpStatus.CONFLICT, "STALE_SUBMISSION", "Đã có bản nộp mới hơn — hãy tải lại và duyệt bản nộp chốt.");
		}
		boolean accepted = "ACCEPTED".equals(decision);
		if (accepted && Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM help_requests WHERE task_id = :t AND status = 'OPEN')")
				.param("t", c.round().taskId()).query(Boolean.class).single())) {
			throw ApiException.of(HttpStatus.CONFLICT, "HELP_REQUEST_BLOCKS_REVIEW",
					"Còn yêu cầu hỗ trợ đang mở giữ quỹ điểm của task — đóng yêu cầu trước khi duyệt.");
		}
		UUID reviewId = jdbc.sql("""
				INSERT INTO task_result_reviews (project_id, submission_id, decision, reviewer_id, issue_description, reviewed_at, created_at)
				VALUES (:p, :s, :d, :u, :i, :at, :at) RETURNING id
				""").param("p", projectId).param("s", submissionId).param("d", decision).param("u", user.id())
				.param("i", accepted ? null : req.issueDescription().strip()).param("at", DbTime.toDb(now)).query(UUID.class).single();
		if (accepted) {
			jdbc.sql("""
					UPDATE task_review_rounds SET state = 'ACCEPTED', accepted_submission_id = :s, completed_at = :done,
					       accepted_at = :at, closed_at = :at, version = version + 1 WHERE id = :r
					""").param("s", submissionId).param("done", DbTime.toDb(t.sub().submittedAt())).param("at", DbTime.toDb(now))
					.param("r", c.round().id()).update();
			var accepted0 = new TaskAcceptedHook.AcceptedSubmission(projectId, c.round().taskId(), c.round().id(), submissionId,
					user.id(), t.sub().submittedBy(), t.sub().submittedAt(), now);
			hooks.orderedStream().forEach(h -> h.onSubmissionAccepted(accepted0));
		}
		else {
			jdbc.sql("UPDATE task_review_rounds SET state = 'CARRYOVER_REQUIRED', version = version + 1 WHERE id = :r")
					.param("r", c.round().id()).update();
			// bản nộp của Helper: work → REVISION_REQUESTED + thông báo cho Helper (cùng transaction)
			supportWorks.ifAvailable(sw -> sw.onTaskSubmissionReviewed(submissionId));
		}
		ops.bumpTask(c.round().taskId());
		String kind = "RESULT_" + decision;
		ops.history(projectId, user.id(), c.round().taskId(), kind, c.round().roundNo(),
				accepted ? s.name(user.id()) + " chấp nhận kết quả lần " + t.sub().no()
						: s.name(user.id()) + (("FIX_REQUIRED".equals(decision)) ? " yêu cầu sửa: " : " yêu cầu cập nhật: ") + req.issueDescription().strip(),
				reviewId);
		ops.audit(projectId, user.id(), "TASK_RESULT_REVIEW", reviewId, "RESULT_REVIEWED", null,
				J.o("submissionId", submissionId, "decision", decision));
		String title = c.display() == null ? "" : c.display().title();
		ops.notify(projectId, "TASK_REVIEWED", "TASK", c.round().taskId(), c.round().cycleId(), "review:" + submissionId + ":reviewed",
				List.of(t.sub().submittedBy()), user.id(),
				accepted ? "Kết quả đã được chấp nhận" : "TL yêu cầu " + ("FIX_REQUIRED".equals(decision) ? "sửa" : "cập nhật") + " kết quả",
				accepted ? "TL đã chấp nhận kết quả task \"" + title + "\"."
						: "Task \"" + title + "\": " + req.issueDescription().strip(),
				J.o("taskId", c.round().taskId(), "groupId", projectId, "submissionId", submissionId, "decision", decision));
		lifecycle.finalizeIfDone(cy.id());
		Rev rev = reader.reviewOf(submissionId).orElseThrow();
		return reviewResponse(s, reload(t), rev, v);
	}

	private Map<String, Object> reviewResponse(Scope s, Target t0, Rev rev, Viewer v) {
		Target t = reload(t0);
		Ctx c = t.c();
		Map<String, Object> award = null;
		if ("ACCEPTED".equals(rev.decision())) {
			award = jdbc.sql("""
					SELECT l.id AS ledger_id, st.id AS settlement_id, l.recipient_id, l.delta_units, m.member_id AS origin_id
					  FROM task_point_settlements st
					  JOIN point_ledger_entries l ON l.settlement_id = st.id AND l.entry_type = 'AWARD'
					  JOIN task_point_budgets b ON b.id = st.budget_id
					  JOIN member_point_budgets m ON m.id = b.origin_member_budget_id
					 WHERE st.accepted_submission_id = :s AND st.revoked_at IS NULL ORDER BY l.created_at LIMIT 1
					""").param("s", rev.submissionId()).query((rs, i) -> {
				UUID recipient = J.uuid(rs, "recipient_id");
				long delta = rs.getLong("delta_units");
				long after = confirmed(s.projectId(), recipient);
				return J.o("ledgerEntryId", J.uuid(rs, "ledger_id"), "settlementId", J.uuid(rs, "settlement_id"),
						"recipient", s.user(recipient), "taskId", c.round().taskId(), "points", J.delta(delta),
						"confirmedBefore", J.amount(after - delta), "confirmedAfter", J.amount(after),
						"source", recipient.equals(J.uuid(rs, "origin_id")) ? "OWN" : "TAKEN_OVER");
			}).optional().orElse(null);
		}
		return J.o("review", views.reviewDto(s, rev),
				"submission", views.submission(s, t.sub(), c.round().taskId(), rev, c.sub() != null && c.sub().id().equals(t.sub().id())),
				"task", views.summary(s, c, v, t.x()), "award", award, "supportWork", null);
	}

	// ------------------------------------------------------------------ chuyển kỳ
	@Transactional
	public ResponseEntityLike carryover(UUID roundId, CurrentUser user, CarryoverRequest r) {
		Ctx c0 = reader.load(roundId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy vòng xử lý."));
		UUID projectId = c0.round().projectId();
		GroupMembership m = access.requireLeader(projectId, user);
		J.Check ck = J.check().require(r.targetCycleId() != null, "targetCycleId", "Bắt buộc")
				.require(r.deadline() != null, "deadline", "Bắt buộc");
		if (r.scopeNote() != null && r.scopeNote().length() > 2000) {
			ck.add("scopeNote", "Tối đa 2000 ký tự");
		}
		if (r.decisionReason() != null && r.decisionReason().length() > 1000) {
			ck.add("decisionReason", "Tối đa 1000 ký tự");
		}
		ck.done();
		ops.lockRound(roundId);
		Ctx c = reader.require(roundId);
		Scope s = scopes.of(projectId);
		CycleRow src = s.cycle(c.round().cycleId());
		Instant now = ops.now();
		if ("CARRIED_OVER".equals(c.round().state())) {
			return replay(s, c, r, m);
		}
		if (!now.isAfter(src.closesAt()) || "ACCEPTED".equals(c.round().state()) || c.effective() == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "CARRYOVER_NOT_ALLOWED", "Vòng này chưa thuộc diện chuyển sang kỳ sau.");
		}
		if (c.sub() != null && c.rev() == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "CARRYOVER_AWAITING_REVIEW", "Bản nộp còn chờ TL duyệt, chưa thể chuyển kỳ.");
		}
		CycleRow target = s.cycleOpt(r.targetCycleId()).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ đích."));
		if (target.sequenceNo() <= src.sequenceNo()) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CARRYOVER_DEADLINE_INVALID", "Kỳ đích phải sau kỳ nguồn.");
		}
		if (target.isClosed(now)) {
			throw ApiException.of(HttpStatus.CONFLICT, "CYCLE_CLOSED", "Kỳ đích đã đóng.");
		}
		if (!r.deadline().isAfter(now) || !target.contains(r.deadline())) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CARRYOVER_DEADLINE_INVALID",
					"Deadline xử lý tiếp phải ở tương lai và nằm trong " + s.cycleLabel(target) + ".");
		}
		Instant start = r.startAt() != null ? r.startAt() : (now.isAfter(target.periodStartAt()) ? now : target.periodStartAt());
		if (start.isAfter(r.deadline())) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CARRYOVER_DEADLINE_INVALID", "Giờ bắt đầu phải trước deadline.");
		}
		boolean adjacent = target.sequenceNo() == src.sequenceNo() + 1;
		if (!adjacent && (r.decisionReason() == null || r.decisionReason().strip().length() < 5)) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "CARRYOVER_REASON_REQUIRED", "Kỳ đích không liền sau: cần ghi lý do.");
		}
		Plan sp = c.effective();
		// 1) đóng vòng nguồn TRƯỚC (unique 'một vòng chưa kết thúc/task'), 2) tạo vòng đích, cùng transaction
		jdbc.sql("UPDATE task_review_rounds SET state = 'CARRIED_OVER', closed_at = :at, version = version + 1 WHERE id = :r")
				.param("at", DbTime.toDb(now)).param("r", roundId).update();
		String reason = c.rev() != null ? c.rev().decision() : "NOT_SUBMITTED";
		UUID targetRound = jdbc.sql("""
				INSERT INTO task_review_rounds (project_id, task_id, round_no, cycle_id, previous_round_id, reason, state, created_by, created_at)
				VALUES (:p, :t, :no, :c, :prev, :reason, 'DRAFT', :u, :at) RETURNING id
				""").param("p", projectId).param("t", c.round().taskId()).param("no", c.round().roundNo() + 1).param("c", target.id())
				.param("prev", roundId).param("reason", reason).param("u", user.id()).param("at", DbTime.toDb(now)).query(UUID.class).single();
		String scope = r.scopeNote() != null && !r.scopeNote().isBlank() ? r.scopeNote().strip()
				: c.rev() != null && c.rev().issue() != null ? sp.description() + "\n\nCần xử lý: " + c.rev().issue() : sp.description();
		UUID plan = jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, proposed_cycle_id, assignee_id, title, description,
				    expected_output, start_at, deadline, difficulty, status, created_by, submitted_by, submitted_at, reviewed_by,
				    reviewed_at, approval_source, created_at, updated_at)
				VALUES (:p, :r, 1, :c, :a, :title, :d, :o, :s, :dl, :df, 'APPROVED', :u, :u, :at, :u, :at, 'TL_CARRYOVER', :at, :at)
				RETURNING id
				""").param("p", projectId).param("r", targetRound).param("c", target.id()).param("a", sp.assigneeId())
				.param("title", sp.title()).param("d", scope).param("o", sp.expectedOutput()).param("s", DbTime.toDb(start))
				.param("dl", DbTime.toDb(r.deadline())).param("df", sp.difficulty()).param("u", user.id()).param("at", DbTime.toDb(now))
				.query(UUID.class).single();
		jdbc.sql("""
				UPDATE task_review_rounds SET effective_plan_id = :pl, state = 'ACTIVE', started_at = :at, work_started_at = :at
				 WHERE id = :r
				""").param("pl", plan).param("at", DbTime.toDb(now)).param("r", targetRound).update();
		jdbc.sql("""
				INSERT INTO task_carryovers (project_id, source_round_id, target_round_id, source_review_id, decided_by, decision_reason, decided_at)
				VALUES (:p, :s, :t, :rev, :u, :why, :at)
				""").param("p", projectId).param("s", roundId).param("t", targetRound).param("rev", c.rev() == null ? null : c.rev().id())
				.param("u", user.id()).param("why", r.decisionReason() != null && !r.decisionReason().isBlank() ? r.decisionReason().strip()
						: "Chuyển sang " + s.cycleLabel(target) + " theo quyết định của TL")
				.param("at", DbTime.toDb(now)).update();
		ops.bumpTask(c.round().taskId());
		ops.history(projectId, user.id(), c.round().taskId(), "CARRIED_OVER", c.round().roundNo() + 1,
				s.name(user.id()) + " chốt deadline xử lý tiếp " + s.instantLabel(r.deadline()) + " (" + s.cycleLabel(target) + ")", targetRound);
		ops.notify(projectId, "TASK_CARRIED_OVER", "TASK", c.round().taskId(), target.id(), "carryover:" + roundId + ":carried-over",
				List.of(sp.assigneeId()), user.id(), "Task được chuyển sang kỳ sau",
				"TL chốt deadline xử lý tiếp cho task \"" + sp.title() + "\": " + s.instantLabel(r.deadline()) + ".",
				J.o("taskId", c.round().taskId(), "groupId", projectId, "roundId", targetRound));
		lifecycle.finalizeIfDone(src.id());
		return new ResponseEntityLike(true, carryoverBody(s, roundId, targetRound, plan, m));
	}

	/** Cùng tham số → 200 replay kết quả hiện có; khác → 409 CARRYOVER_ALREADY_DONE. */
	private ResponseEntityLike replay(Scope s, Ctx c, CarryoverRequest r, GroupMembership m) {
		Object[] co = jdbc.sql("SELECT target_round_id FROM task_carryovers WHERE source_round_id = :r").param("r", c.round().id())
				.query((rs, i) -> new Object[] { J.uuid(rs, "target_round_id") }).optional().orElse(null);
		if (co == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "CARRYOVER_NOT_ALLOWED", "Vòng này không thuộc diện chuyển.");
		}
		UUID targetRound = (UUID) co[0];
		Ctx tc = reader.require(targetRound);
		boolean same = Objects.equals(tc.round().cycleId(), r.targetCycleId()) && tc.effective() != null
				&& Objects.equals(tc.effective().deadline(), r.deadline());
		if (!same) {
			throw ApiException.of(HttpStatus.CONFLICT, "CARRYOVER_ALREADY_DONE", "Vòng này đã được chuyển kỳ với tham số khác.");
		}
		return new ResponseEntityLike(false, carryoverBody(s, c.round().id(), targetRound, tc.effective().id(), m));
	}

	private Map<String, Object> carryoverBody(Scope s, UUID source, UUID target, UUID plan, GroupMembership m) {
		Ctx sc = reader.require(source);
		Ctx tc = reader.require(target);
		return J.o("sourceRound", views.round(s, sc), "targetRound", views.round(s, tc), "plan", views.plan(s, tc.effective(), tc),
				"task", views.detail(s, tc, viewer(m)));
	}

	/** Phân biệt 201 (tạo mới) và 200 (replay) cho controller. */
	public record ResponseEntityLike(boolean created, Map<String, Object> body) {
	}

	// ------------------------------------------------------------------ hàng đợi duyệt
	@Transactional(readOnly = true)
	public Map<String, Object> reviewQueue(UUID projectId, UUID cycleId, CurrentUser user) {
		GroupMembership m = access.requireMember(projectId, user);
		Scope s = scopes.of(projectId);
		CycleRow cy = s.cycleOpt(cycleId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint."));
		Viewer v = viewer(m);
		List<Row> rows = data.rows(s, cy);
		Reports rep = data.reports(cy);
		Extras x = views.extras(rows.stream().map(Row::ctx).toList());
		boolean closed = s.now().isAfter(cy.closesAt());
		List<Map<String, Object>> awaiting = new ArrayList<>();
		List<Map<String, Object>> carry = new ArrayList<>();
		List<Map<String, Object>> reviewed = new ArrayList<>();
		boolean any = false;
		for (Row r : rows) {
			Ctx c = r.ctx();
			if (!m.isLeader() && !user.id().equals(c.round().independentApproverId())) {
				continue;
			}
			any = true;
			Eval e = data.eval(s, cy, r);
			Map<String, Object> row = data.taskRow(s, cy, r, rep, m.isLeader() || user.id().equals(r.assigneeId()));
			Map<String, Object> sub = c.sub() == null ? null : views.submission(s, c.sub(), r.taskId(), c.rev(), true);
			boolean canNow = closed && c.sub() != null && c.rev() == null && !List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state())
					&& TaskViews.reviewAuth(s, c, v, x.points().get(r.taskId())) == Auth.OK;
			Map<String, Object> item = J.o("row", row, "submission", sub, "canReviewNow", canNow);
			if (e.needsCarryover()) {
				carry.add(J.o("row", row, "submission", sub, "canReviewNow", false));
			}
			else if (c.sub() != null && c.rev() == null && !List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state())) {
				awaiting.add(item);
			}
			else if (c.rev() != null || List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state())) {
				reviewed.add(J.o("row", row, "submission", sub, "canReviewNow", false));
			}
		}
		if (!m.isLeader() && !any) {
			throw ApiException.of(ErrorCode.LEAD_ONLY, null);
		}
		return J.o("cycle", s.cycleDto(cy), "serverTime", s.now(), "canReviewNow", closed, "awaitingReview", awaiting,
				"carryoverRequired", carry, "reviewed", reviewed);
	}
}
