package app.hanare.task;

import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Eval;
import app.hanare.task.TaskReader.Plan;
import app.hanare.task.TaskReader.Rev;
import app.hanare.task.TaskReader.Sub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Dựng JSON {@code TaskSummary/TaskDetail/PlanRevision/TaskSubmission/TaskRound} đúng api-types.ts (server tính mọi nhãn). */
@Component
public class TaskViews {

	/** Người gọi: id + vai trò trong nhóm. */
	public record Viewer(UUID id, String role) {
		public boolean lead() {
			return "LEAD".equals(role);
		}
	}

	/** Điểm của một task (đọc thuần SQL từ bảng của BE-B2; có thể rỗng). */
	public record Pt(UUID budgetId, long units, UUID origin, UUID recipient, boolean held, long net) {
	}

	/** Dữ liệu phụ đọc theo lô cho nhiều task. */
	public record Extras(Map<UUID, Pt> points, Map<UUID, UUID> openHelp, Map<UUID, String> risk) {
		static Extras empty() {
			return new Extras(Map.of(), Map.of(), Map.of());
		}
	}

	/** Kết quả kiểm tra quyền duyệt (spec 4.4). */
	public enum Auth { OK, NOT_AUTHORIZED, INDEPENDENT_REQUIRED, SELF }

	private final JdbcClient jdbc;
	private final TaskReader reader;
	private final JsonMapper mapper;

	public TaskViews(JdbcClient jdbc, TaskReader reader, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.reader = reader;
		this.mapper = mapper;
	}

	// ------------------------------------------------------------------ dữ liệu phụ
	public Extras extras(Collection<Ctx> ctxs) {
		if (ctxs.isEmpty()) {
			return Extras.empty();
		}
		List<UUID> taskIds = ctxs.stream().map(c -> c.round().taskId()).distinct().toList();
		List<UUID> roundIds = ctxs.stream().map(c -> c.round().id()).toList();
		Map<UUID, Pt> points = new HashMap<>();
		jdbc.sql("""
				SELECT b.task_id, b.id AS budget_id, b.point_units, m.member_id AS origin_id, a.recipient_id,
				       EXISTS (SELECT 1 FROM task_point_reservations r WHERE r.budget_id = b.id AND r.state = 'HELD') AS held,
				       COALESCE((SELECT SUM(l.delta_units) FROM point_ledger_entries l WHERE l.budget_id = b.id), 0) AS net
				  FROM task_point_budgets b
				  JOIN member_point_budgets m ON m.id = b.origin_member_budget_id
				  LEFT JOIN task_point_assignments a ON a.budget_id = b.id AND a.status = 'CURRENT'
				 WHERE b.task_id IN (:ids) AND b.state = 'FUNDED'
				""").param("ids", taskIds).query((rs, i) -> {
			points.put(J.uuid(rs, "task_id"), new Pt(J.uuid(rs, "budget_id"), rs.getLong("point_units"), J.uuid(rs, "origin_id"),
					J.uuid(rs, "recipient_id"), rs.getBoolean("held"), rs.getLong("net")));
			return 0;
		}).list();
		Map<UUID, UUID> help = new HashMap<>();
		jdbc.sql("SELECT task_id, id FROM help_requests WHERE task_id IN (:ids) AND status IN ('OPEN','MATCHED','IN_PROGRESS','SUBMITTED')")
				.param("ids", taskIds).query((rs, i) -> {
					help.put(J.uuid(rs, "task_id"), J.uuid(rs, "id"));
					return 0;
				}).list();
		Map<UUID, String> risk = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (round_id) round_id, risk FROM (
				    SELECT round_id, risk_status AS risk, created_at AS at FROM task_progress_notes
				     WHERE round_id IN (:ids) AND risk_status IS NOT NULL
				    UNION ALL
				    SELECT i.round_id, i.risk_status, rep.submitted_at FROM checkpoint_report_items i
				      JOIN checkpoint_reports rep ON rep.id = i.report_id
				     WHERE i.round_id IN (:ids) AND rep.status = 'SUBMITTED') x
				 ORDER BY round_id, at DESC
				""").param("ids", roundIds).query((rs, i) -> {
			risk.put(J.uuid(rs, "round_id"), rs.getString("risk"));
			return 0;
		}).list();
		return new Extras(points, help, risk);
	}

	// ------------------------------------------------------------------ quyền duyệt (spec 4.4)
	public static Auth reviewAuth(Scope s, Ctx c, Viewer v, Pt pt) {
		UUID executor = c.sub() != null ? c.sub().submittedBy() : c.executor();
		boolean executorIsLead = executor != null && executor.equals(s.leaderId());
		UUID approver = c.round().independentApproverId();
		boolean self = pt != null && pt.recipient() != null && pt.recipient().equals(v.id());
		if (executorIsLead && pt != null) {
			if (v.id().equals(approver)) {
				return self ? Auth.SELF : Auth.OK;
			}
			if (approver == null && v.lead()) {
				return Auth.INDEPENDENT_REQUIRED;
			}
			return self ? Auth.SELF : Auth.NOT_AUTHORIZED;
		}
		if (self) {
			return Auth.SELF;
		}
		if (executorIsLead) {
			return v.lead() || v.id().equals(approver) ? Auth.OK : Auth.NOT_AUTHORIZED;
		}
		return v.lead() ? Auth.OK : Auth.NOT_AUTHORIZED;
	}

	/** Người được phép duyệt bản nộp này (để hiển thị). */
	public static UUID authorizedReviewer(Scope s, Ctx c, Pt pt) {
		UUID executor = c.sub() != null ? c.sub().submittedBy() : c.executor();
		boolean executorIsLead = executor != null && executor.equals(s.leaderId());
		return executorIsLead && pt != null ? c.round().independentApproverId() : s.leaderId();
	}

	// ------------------------------------------------------------------ hành động được phép
	public static List<String> allowed(Scope s, Ctx c, Eval e, Viewer v, Extras x) {
		List<String> a = new ArrayList<>();
		Instant now = s.now();
		UUID me = v.id();
		boolean isExec = me.equals(c.executor());
		CycleRow cyc = s.cycle(c.round().cycleId());
		boolean closed = now.isAfter(cyc.closesAt());
		Plan open = c.open();
		Plan eff = c.effective();
		String state = c.round().state();
		boolean active = "ACTIVE".equals(state) || "AWAITING_REVIEW".equals(state);
		boolean finished = "ACCEPTED".equals(state) || "CARRIED_OVER".equals(state);
		if (open != null && List.of("DRAFT", "CHANGES_REQUESTED").contains(open.status())) {
			if (me.equals(open.assigneeId()) || (v.lead() && "DRAFT".equals(open.status()))) {
				a.add("EDIT_PLAN");
			}
			if (me.equals(open.assigneeId())) {
				a.add("SUBMIT_PLAN");
			}
		}
		if (open != null && "PENDING_APPROVAL".equals(open.status())) {
			if (me.equals(open.submittedBy())) {
				a.add("WITHDRAW_PLAN");
			}
			if (v.lead()) {
				a.add("REVIEW_PLAN");
			}
		}
		if (eff != null && active && !closed && open == null && isExec) {
			a.add("PROPOSE_CHANGE");
		}
		if (eff != null && "ACTIVE".equals(state) && isExec && "TODO".equals(e.taskStatus())) {
			a.add("START");
		}
		if (eff != null && active && isExec && !closed) {
			a.add("UPDATE_PROGRESS");
			a.add("SUBMIT_RESULT");
		}
		if (eff != null && isExec && !finished && cyc.reportWindowOpen(now)) {
			a.add("REPORT_CHECKPOINT");
		}
		if (eff != null && isExec && !finished) {
			a.add("REPORT_DIFFICULTY");
		}
		if (c.sub() != null && c.rev() == null && closed && "AWAITING_REVIEW".equals(state)
				&& reviewAuth(s, c, v, x.points().get(c.round().taskId())) == Auth.OK) {
			a.add("REVIEW_RESULT");
		}
		if (v.lead() && e.needsCarryover()) {
			a.add("SET_CARRYOVER");
		}
		if (v.lead() && Objects.equals(c.executor(), s.leaderId()) && !"ACCEPTED".equals(state)) {
			a.add("DESIGNATE_REVIEWER");
		}
		if (c.sub() != null) {
			a.add("VIEW_RESULT");
		}
		return a;
	}

	// ------------------------------------------------------------------ TaskSummary
	public Map<String, Object> summary(Scope s, Ctx c, Viewer v, Extras x) {
		CycleRow cyc = s.cycle(c.cycleId());
		Eval e = TaskReader.eval(c, c.deadline(), s.now(), s.cycle(c.round().cycleId()).closesAt(), false);
		Plan d = c.display();
		UUID taskId = c.round().taskId();
		Pt pt = x.points().get(taskId);
		String planStatus = c.effective() != null ? "APPROVED" : d == null ? "DRAFT" : d.status();
		return J.o(
				"id", taskId, "groupId", s.projectId(), "title", d == null ? "" : d.title(),
				"difficulty", d == null ? null : d.difficulty(),
				"originalAssignee", s.user(c.originalAssignee()), "currentExecutor", s.user(c.executor()),
				"cycle", s.cycleRef(cyc), "roundId", c.round().id(), "roundNo", c.round().roundNo(),
				"startAt", d == null ? null : d.startAt(), "deadline", c.deadline(), "originalDeadline", c.originalDeadline(),
				"committed", c.committed(), "planStatus", planStatus,
				"pendingRevisionStatus", c.effective() != null && c.open() != null ? c.open().status() : null,
				"pendingRevisionId", c.effective() != null && c.open() != null ? c.open().id() : null,
				"taskStatus", e.taskStatus(), "riskStatus", x.risk().getOrDefault(c.round().id(), "NORMAL"),
				"timeliness", e.timeliness(), "timelinessLabel", e.label(), "isLate", e.isLate(),
				"needsCarryover", e.needsCarryover(), "carryoverReason", e.carryoverReason(),
				"latestSubmittedAt", c.sub() == null ? null : c.sub().submittedAt(),
				"points", pointsBrief(s, pt, e, v), "hasOpenHelpRequest", x.openHelp().containsKey(taskId),
				"version", c.round().taskVersion(), "allowedActions", allowed(s, c, e, v, x));
	}

	private Map<String, Object> pointsBrief(Scope s, Pt pt, Eval e, Viewer v) {
		if (pt == null) {
			return null;
		}
		UUID recipient = pt.recipient() != null ? pt.recipient() : pt.origin();
		String status;
		if (pt.net() > 0) {
			status = "AWARDED";
		}
		else if (!recipient.equals(pt.origin()) && v.id().equals(pt.origin())) {
			status = "TRANSFERRED_OUT";
		}
		else {
			status = switch (e.taskStatus()) {
				case "SUBMITTED", "DONE" -> "AWAITING_REVIEW";
				case "FIX_REQUIRED", "UPDATE_REQUIRED" -> "NEEDS_REVISION";
				case "IN_PROGRESS" -> "IN_PROGRESS";
				default -> "NOT_STARTED";
			};
		}
		return J.o("amount", J.amount(pt.units()), "status", status, "originalAssignee", s.user(pt.origin()),
				"currentRecipient", s.user(recipient), "heldForHelpRequest", pt.held(), "awarded", pt.net() > 0);
	}

	public List<Map<String, Object>> summaries(Scope s, Collection<Ctx> ctxs, Viewer v) {
		Extras x = extras(ctxs);
		return ctxs.stream().map(c -> summary(s, c, v, x)).toList();
	}

	public Map<String, Object> summaryOf(Scope s, UUID roundId, Viewer v) {
		Ctx c = reader.require(roundId);
		return summary(s, c, v, extras(List.of(c)));
	}

	// ------------------------------------------------------------------ PlanRevision
	public Map<String, Object> plan(Scope s, Plan p, Ctx c) {
		return J.o("id", p.id(), "taskId", c.round().taskId(), "roundId", p.roundId(), "revisionNo", p.revisionNo(),
				"baseRevisionId", p.baseRevisionId(), "status", p.status(),
				"proposedCycle", p.proposedCycleId() == null ? s.cycleRef(c.round().cycleId()) : s.cycleRef(p.proposedCycleId()),
				"assignee", s.user(p.assigneeId()), "title", p.title(), "description", p.description(),
				"expectedOutput", p.expectedOutput(), "startAt", p.startAt(), "deadline", p.deadline(),
				"difficulty", p.difficulty(), "approvalSource", p.approvalSource() == null ? "MEMBER_PLAN" : p.approvalSource(),
				"changeReason", p.changeReason(), "submittedBy", s.user(p.submittedBy()), "submittedAt", p.submittedAt(),
				"reviewedBy", s.user(p.reviewedBy()), "reviewedAt", p.reviewedAt(), "reviewNote", p.reviewNote(),
				"changes", changes(s, p), "isEffective", p.id().equals(c.round().effectivePlanId()),
				"createdAt", p.createdAt(), "updatedAt", p.updatedAt(), "version", p.version());
	}

	private List<Map<String, Object>> changes(Scope s, Plan p) {
		if (p.baseRevisionId() == null) {
			return List.of();
		}
		Plan b = reader.plan(p.baseRevisionId()).orElse(null);
		if (b == null) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		diff(out, "title", b.title(), p.title(), b.title(), p.title());
		diff(out, "description", b.description(), p.description(), null, null);
		diff(out, "expectedOutput", b.expectedOutput(), p.expectedOutput(), null, null);
		if (!Objects.equals(b.proposedCycleId(), p.proposedCycleId())) {
			out.add(J.o("field", "cycleId", "oldValue", str(b.proposedCycleId()), "newValue", str(p.proposedCycleId()),
					"oldLabel", b.proposedCycleId() == null ? null : s.cycleLabel(s.cycle(b.proposedCycleId())),
					"newLabel", p.proposedCycleId() == null ? null : s.cycleLabel(s.cycle(p.proposedCycleId()))));
		}
		if (!Objects.equals(b.assigneeId(), p.assigneeId())) {
			out.add(J.o("field", "assigneeId", "oldValue", str(b.assigneeId()), "newValue", str(p.assigneeId()),
					"oldLabel", b.assigneeId() == null ? null : s.name(b.assigneeId()),
					"newLabel", p.assigneeId() == null ? null : s.name(p.assigneeId())));
		}
		timeDiff(out, s, "startAt", b.startAt(), p.startAt());
		timeDiff(out, s, "deadline", b.deadline(), p.deadline());
		if (!Objects.equals(b.difficulty(), p.difficulty())) {
			out.add(J.o("field", "difficulty", "oldValue", b.difficulty(), "newValue", p.difficulty(),
					"oldLabel", difficultyLabel(b.difficulty()), "newLabel", difficultyLabel(p.difficulty())));
		}
		return out;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private static void diff(List<Map<String, Object>> out, String field, String o, String n, String ol, String nl) {
		if (!Objects.equals(o, n)) {
			out.add(J.o("field", field, "oldValue", o, "newValue", n, "oldLabel", ol, "newLabel", nl));
		}
	}

	private static void timeDiff(List<Map<String, Object>> out, Scope s, String field, Instant o, Instant n) {
		if (!Objects.equals(o, n)) {
			out.add(J.o("field", field, "oldValue", J.t(o), "newValue", J.t(n), "oldLabel", s.instantLabel(o),
					"newLabel", s.instantLabel(n)));
		}
	}

	public static String difficultyLabel(String d) {
		return d == null ? null : switch (d) {
			case "EASY" -> "Dễ";
			case "MEDIUM" -> "Trung bình";
			case "HARD" -> "Khó";
			default -> d;
		};
	}

	// ------------------------------------------------------------------ TaskSubmission / review
	public List<Object> evidence(String json) {
		@SuppressWarnings("unchecked")
		List<Object> l = json == null ? List.of() : mapper.readValue(json, List.class);
		return l;
	}

	public String evidenceJson(Object evidence) {
		return mapper.writeValueAsString(evidence);
	}

	public Map<String, Object> reviewDto(Scope s, Rev r) {
		return r == null ? null : J.o("id", r.id(), "submissionId", r.submissionId(), "decision", r.decision(),
				"reviewer", s.user(r.reviewerId()), "issueDescription", r.issue(), "reviewedAt", r.reviewedAt());
	}

	/** {@code isReviewTarget}: lần nộp mới nhất của vòng. */
	public Map<String, Object> submission(Scope s, Sub sub, UUID taskId, Rev rev, boolean reviewTarget) {
		boolean onTime = sub.planDeadline() == null || !sub.submittedAt().isAfter(sub.planDeadline());
		return J.o("id", sub.id(), "roundId", sub.roundId(), "taskId", taskId, "planRevisionId", sub.planRevisionId(),
				"submissionNo", sub.no(), "submittedBy", s.user(sub.submittedBy()), "resultNote", sub.resultNote(),
				"evidence", evidence(sub.evidenceJson()), "submittedAt", sub.submittedAt(), "deadline", sub.planDeadline(),
				"isOnTime", onTime, "isReviewTarget", reviewTarget, "review", reviewDto(s, rev), "supportSubmissionId", null);
	}

	public List<Map<String, Object>> submissions(Scope s, Ctx c) {
		List<Sub> subs = reader.submissionsOfRound(c.round().id());
		Map<UUID, Rev> revs = new HashMap<>();
		for (Sub sub : subs) {
			reader.reviewOf(sub.id()).ifPresent(r -> revs.put(sub.id(), r));
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < subs.size(); i++) {
			out.add(submission(s, subs.get(i), c.round().taskId(), revs.get(subs.get(i).id()), i == subs.size() - 1));
		}
		return out;
	}

	// ------------------------------------------------------------------ TaskRound / TaskDetail
	public Map<String, Object> round(Scope s, Ctx c) {
		Map<String, Object> carry = jdbc.sql("""
				SELECT id, source_round_id, target_round_id, decided_by, decision_reason, decided_at
				  FROM task_carryovers WHERE source_round_id = :r
				""").param("r", c.round().id()).query((rs, i) -> J.o("id", J.uuid(rs, "id"), "sourceRoundId",
				J.uuid(rs, "source_round_id"), "targetRoundId", J.uuid(rs, "target_round_id"), "decidedBy",
				s.user(J.uuid(rs, "decided_by")), "decisionReason", rs.getString("decision_reason"), "decidedAt",
				J.inst(rs, "decided_at"))).optional().orElse(null);
		return J.o("id", c.round().id(), "taskId", c.round().taskId(), "roundNo", c.round().roundNo(),
				"reason", c.round().reason(), "state", c.round().state(), "cycle", s.cycleRef(c.cycleId()),
				"previousRoundId", c.round().previousRoundId(), "effectivePlanId", c.round().effectivePlanId(),
				"deadline", c.deadline(), "decision", c.rev() == null ? null : c.rev().decision(),
				"startedAt", c.round().startedAt(), "closedAt", c.round().closedAt(), "carryover", carry);
	}

	public Map<String, Object> lastProgress(Scope s, UUID roundId) {
		return jdbc.sql("""
				SELECT id, task_id, author_id, note, risk_status, expected_completion_at, created_at
				  FROM task_progress_notes WHERE round_id = :r ORDER BY created_at DESC, id LIMIT 1
				""").param("r", roundId).query((rs, i) -> progressDto(s, rs)).optional().orElse(null);
	}

	public static Map<String, Object> progressDto(Scope s, java.sql.ResultSet rs) throws java.sql.SQLException {
		return J.o("id", J.uuid(rs, "id"), "taskId", J.uuid(rs, "task_id"), "by", s.user(J.uuid(rs, "author_id")),
				"note", rs.getString("note"), "riskStatus", rs.getString("risk_status"),
				"expectedCompletionAt", J.inst(rs, "expected_completion_at"), "createdAt", J.inst(rs, "created_at"));
	}

	public String reportState(UUID cycleId, UUID memberId) {
		List<String> st = jdbc.sql("""
				SELECT r.status FROM checkpoint_reports r JOIN checkpoint_participants p ON p.id = r.participant_id
				 WHERE p.cycle_id = :c AND p.member_id = :m
				""").param("c", cycleId).param("m", memberId).query(String.class).list();
		return st.contains("SUBMITTED") ? "SUBMITTED" : st.contains("DRAFT") ? "DRAFT" : "NOT_RESPONDED";
	}

	public Map<String, Object> detail(Scope s, Ctx c, Viewer v) {
		Extras x = extras(List.of(c));
		Map<String, Object> m = new java.util.LinkedHashMap<>(summary(s, c, v, x));
		Plan d = c.display();
		m.put("description", d == null ? null : d.description());
		m.put("expectedOutput", d == null ? null : d.expectedOutput());
		m.put("effectivePlan", c.effective() == null ? null : plan(s, c.effective(), c));
		m.put("pendingRevision", c.open() == null ? null : plan(s, c.open(), c));
		m.put("currentRound", round(s, c));
		List<UUID> roundIds = jdbc.sql("SELECT id FROM task_review_rounds WHERE task_id = :t ORDER BY round_no")
				.param("t", c.round().taskId()).query(UUID.class).list();
		Map<UUID, Ctx> all = reader.loadAll(roundIds);
		m.put("rounds", roundIds.stream().map(id -> round(s, all.get(id))).toList());
		m.put("submissions", submissions(s, c));
		m.put("acceptedSubmissionId", c.round().acceptedSubmissionId());
		m.put("completedAt", c.round().completedAt());
		m.put("acceptedAt", c.round().acceptedAt());
		boolean late = false;
		if (c.round().acceptedSubmissionId() != null && c.sub() != null) {
			late = c.sub().planDeadline() != null && c.sub().submittedAt().isAfter(c.sub().planDeadline());
		}
		m.put("completedLate", late);
		m.put("independentReviewer", s.user(c.round().independentApproverId()));
		m.put("lastProgress", lastProgress(s, c.round().id()));
		m.put("helpRequestId", x.openHelp().get(c.round().taskId()));
		boolean mine = c.committed() && v.id().equals(c.executor());
		m.put("checkpoint", mine ? J.o("cycle", s.cycleRef(c.round().cycleId()), "myReportState",
				reportState(c.round().cycleId(), v.id()), "reportWindowOpen", s.cycle(c.round().cycleId()).reportWindowOpen(s.now()))
				: null);
		return m;
	}

	/** Dòng hàng đợi/báo cáo: deadline + trạng thái đúng hạn tính theo vòng (không theo trạng thái hiện tại của task). */
	public Map<String, Object> toPlanQueue(Scope s, Plan p, Ctx c) {
		return J.o("planId", p.id(), "taskId", c.round().taskId(), "taskTitle", p.title(), "assignee", s.user(p.assigneeId()),
				"submittedAt", p.submittedAt(), "isChange", p.baseRevisionId() != null);
	}
}
