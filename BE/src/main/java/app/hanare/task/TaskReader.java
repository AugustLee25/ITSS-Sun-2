package app.hanare.task;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Đọc dữ liệu một/nhiều vòng xử lý (vòng + bản kế hoạch hiệu lực/đang mở + lần nộp mới nhất + quyết định) theo lô. */
@Component
public class TaskReader {

	public record Round(UUID id, UUID taskId, UUID projectId, int roundNo, UUID cycleId, UUID previousRoundId, String reason,
			String state, UUID effectivePlanId, UUID acceptedSubmissionId, Instant completedAt, Instant acceptedAt,
			Instant startedAt, Instant workStartedAt, Instant closedAt, long taskVersion, UUID taskCreatedBy,
			Instant taskCreatedAt, UUID independentApproverId) {
	}

	public record Plan(UUID id, UUID roundId, int revisionNo, UUID baseRevisionId, String status, UUID proposedCycleId,
			UUID assigneeId, String title, String description, String expectedOutput, Instant startAt, Instant deadline,
			String difficulty, UUID createdBy, UUID submittedBy, Instant submittedAt, UUID reviewedBy, Instant reviewedAt,
			String reviewNote, String approvalSource, String changeReason, Instant createdAt, Instant updatedAt, long version) {
	}

	public record Sub(UUID id, UUID roundId, int no, UUID planRevisionId, UUID submittedBy, Instant submittedAt,
			String resultNote, String evidenceJson, Instant planDeadline) {
	}

	public record Rev(UUID id, UUID submissionId, String decision, UUID reviewerId, String issue, Instant reviewedAt) {
	}

	/** Toàn cảnh một vòng xử lý. */
	public record Ctx(Round round, Plan effective, Plan open, Sub sub, Rev rev, UUID originalAssignee, Instant originalDeadline) {

		public Plan display() {
			return effective != null ? effective : open;
		}

		/** Người thực hiện hiện tại (bản có hiệu lực, hoặc người được giao trên bản nháp). */
		public UUID executor() {
			Plan p = display();
			return p == null ? null : p.assigneeId();
		}

		public boolean committed() {
			return effective != null;
		}

		public Instant deadline() {
			Plan p = display();
			return p == null ? null : p.deadline();
		}

		public UUID cycleId() {
			return effective == null && open != null && open.proposedCycleId() != null ? open.proposedCycleId() : round.cycleId();
		}
	}

	/** Kết quả đánh giá thực hiện/đúng hạn của một vòng (spec 4.1). */
	public record Eval(String taskStatus, String timeliness, String label, boolean isLate, boolean needsCarryover,
			String carryoverReason) {
	}

	private final JdbcClient jdbc;

	public TaskReader(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static final String PLAN_COLS = """
			p.id, p.round_id, p.revision_no, p.base_revision_id, p.status, p.proposed_cycle_id, p.assignee_id, p.title,
			p.description, p.expected_output, p.start_at, p.deadline, p.difficulty, p.created_by, p.submitted_by,
			p.submitted_at, p.reviewed_by, p.reviewed_at, p.review_note, p.approval_source, p.change_reason, p.created_at,
			p.updated_at, p.version""";

	static final RowMapper<Plan> PLAN_MAPPER = (rs, i) -> new Plan(J.uuid(rs, "id"), J.uuid(rs, "round_id"),
			rs.getInt("revision_no"), J.uuid(rs, "base_revision_id"), rs.getString("status"), J.uuid(rs, "proposed_cycle_id"),
			J.uuid(rs, "assignee_id"), rs.getString("title"), rs.getString("description"), rs.getString("expected_output"),
			J.inst(rs, "start_at"), J.inst(rs, "deadline"), rs.getString("difficulty"), J.uuid(rs, "created_by"),
			J.uuid(rs, "submitted_by"), J.inst(rs, "submitted_at"), J.uuid(rs, "reviewed_by"), J.inst(rs, "reviewed_at"),
			rs.getString("review_note"), rs.getString("approval_source"), rs.getString("change_reason"),
			J.inst(rs, "created_at"), J.inst(rs, "updated_at"), rs.getLong("version"));

	static final RowMapper<Sub> SUB_MAPPER = (rs, i) -> new Sub(J.uuid(rs, "id"), J.uuid(rs, "round_id"),
			rs.getInt("submission_no"), J.uuid(rs, "plan_revision_id"), J.uuid(rs, "submitted_by"), J.inst(rs, "submitted_at"),
			rs.getString("result_note"), rs.getString("evidence"), J.inst(rs, "plan_deadline"));

	static final String SUB_SELECT = """
			SELECT s.id, s.round_id, s.submission_no, s.plan_revision_id, s.submitted_by, s.submitted_at, s.result_note,
			       s.evidence::text AS evidence, p.deadline AS plan_deadline
			  FROM task_submissions s JOIN task_plan_revisions p ON p.id = s.plan_revision_id""";

	// ------------------------------------------------------------------ nạp
	public Optional<Ctx> load(UUID roundId) {
		return Optional.ofNullable(loadAll(List.of(roundId)).get(roundId));
	}

	public Ctx require(UUID roundId) {
		return load(roundId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy vòng xử lý."));
	}

	public Map<UUID, Ctx> loadAll(Collection<UUID> roundIds) {
		Map<UUID, Ctx> out = new LinkedHashMap<>();
		if (roundIds.isEmpty()) {
			return out;
		}
		Map<UUID, Round> rounds = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT r.id, r.task_id, r.project_id, r.round_no, r.cycle_id, r.previous_round_id, r.reason, r.state,
				       r.effective_plan_id, r.accepted_submission_id, r.completed_at, r.accepted_at, r.started_at,
				       r.work_started_at, r.closed_at, t.version AS task_version, t.created_by AS task_created_by,
				       t.created_at AS task_created_at, t.independent_approver_id
				  FROM task_review_rounds r JOIN tasks t ON t.id = r.task_id
				 WHERE r.id IN (:ids)
				""").param("ids", roundIds).query((rs, i) -> {
			Round r = new Round(J.uuid(rs, "id"), J.uuid(rs, "task_id"), J.uuid(rs, "project_id"), rs.getInt("round_no"),
					J.uuid(rs, "cycle_id"), J.uuid(rs, "previous_round_id"), rs.getString("reason"), rs.getString("state"),
					J.uuid(rs, "effective_plan_id"), J.uuid(rs, "accepted_submission_id"), J.inst(rs, "completed_at"),
					J.inst(rs, "accepted_at"), J.inst(rs, "started_at"), J.inst(rs, "work_started_at"), J.inst(rs, "closed_at"),
					rs.getLong("task_version"), J.uuid(rs, "task_created_by"), J.inst(rs, "task_created_at"),
					J.uuid(rs, "independent_approver_id"));
			rounds.put(r.id(), r);
			return 0;
		}).list();
		if (rounds.isEmpty()) {
			return out;
		}
		Map<UUID, Plan> effective = new HashMap<>();
		Map<UUID, Plan> open = new HashMap<>();
		jdbc.sql("SELECT " + PLAN_COLS + ", r.effective_plan_id AS eff FROM task_plan_revisions p JOIN task_review_rounds r ON r.id = p.round_id"
				+ " WHERE r.id IN (:ids) AND (p.status <> 'APPROVED' OR p.id = r.effective_plan_id)")
				.param("ids", rounds.keySet()).query((rs, i) -> {
					Plan p = PLAN_MAPPER.mapRow(rs, i);
					if ("APPROVED".equals(p.status())) {
						effective.put(p.roundId(), p);
					}
					else {
						open.put(p.roundId(), p);
					}
					return 0;
				}).list();
		Map<UUID, Sub> subs = new HashMap<>();
		jdbc.sql("SELECT DISTINCT ON (s.round_id) " + SUB_SELECT.substring("SELECT ".length())
				+ " WHERE s.round_id IN (:ids) ORDER BY s.round_id, s.submission_no DESC")
				.param("ids", rounds.keySet()).query((rs, i) -> {
					Sub s = SUB_MAPPER.mapRow(rs, i);
					subs.put(s.roundId(), s);
					return 0;
				}).list();
		Map<UUID, Rev> revs = new HashMap<>();
		if (!subs.isEmpty()) {
			jdbc.sql("""
					SELECT id, submission_id, decision, reviewer_id, issue_description, reviewed_at
					  FROM task_result_reviews WHERE submission_id IN (:ids)
					""").param("ids", subs.values().stream().map(Sub::id).toList()).query((rs, i) -> {
				Rev v = new Rev(J.uuid(rs, "id"), J.uuid(rs, "submission_id"), rs.getString("decision"),
						J.uuid(rs, "reviewer_id"), rs.getString("issue_description"), J.inst(rs, "reviewed_at"));
				revs.put(v.submissionId(), v);
				return 0;
			}).list();
		}
		// người phụ trách gốc + deadline gốc (vòng 1)
		Map<UUID, UUID> origAssignee = new HashMap<>();
		Map<UUID, Instant> origDeadline = new HashMap<>();
		List<UUID> taskIds = rounds.values().stream().map(Round::taskId).distinct().toList();
		jdbc.sql("""
				SELECT r.task_id, p.assignee_id FROM task_review_rounds r JOIN task_plan_revisions p ON p.round_id = r.id
				 WHERE r.task_id IN (:ids) AND r.round_no = 1 AND p.revision_no = 1
				""").param("ids", taskIds).query((rs, i) -> {
			origAssignee.put(J.uuid(rs, "task_id"), J.uuid(rs, "assignee_id"));
			return 0;
		}).list();
		jdbc.sql("""
				SELECT DISTINCT ON (r.task_id) r.task_id, p.deadline FROM task_review_rounds r
				  JOIN task_plan_revisions p ON p.round_id = r.id
				 WHERE r.task_id IN (:ids) AND r.round_no = 1 AND p.status = 'APPROVED'
				 ORDER BY r.task_id, p.revision_no
				""").param("ids", taskIds).query((rs, i) -> {
			origDeadline.put(J.uuid(rs, "task_id"), J.inst(rs, "deadline"));
			return 0;
		}).list();
		for (Round r : rounds.values()) {
			Sub s = subs.get(r.id());
			out.put(r.id(), new Ctx(r, effective.get(r.id()), open.get(r.id()), s, s == null ? null : revs.get(s.id()),
					origAssignee.get(r.taskId()), origDeadline.get(r.taskId())));
		}
		return out;
	}

	/** Vòng mới nhất của mỗi task chưa lưu trữ trong nhóm. */
	public List<UUID> currentRoundIds(UUID projectId) {
		return jdbc.sql("""
				SELECT DISTINCT ON (r.task_id) r.id FROM task_review_rounds r JOIN tasks t ON t.id = r.task_id
				 WHERE r.project_id = :p AND t.archived_at IS NULL ORDER BY r.task_id, r.round_no DESC
				""").param("p", projectId).query(UUID.class).list();
	}

	public Optional<UUID> currentRoundOf(UUID taskId) {
		return jdbc.sql("SELECT id FROM task_review_rounds WHERE task_id = :t ORDER BY round_no DESC LIMIT 1")
				.param("t", taskId).query(UUID.class).optional();
	}

	public Optional<UUID> projectOfTask(UUID taskId) {
		return jdbc.sql("SELECT project_id FROM tasks WHERE id = :t").param("t", taskId).query(UUID.class).optional();
	}

	public List<Plan> plansOfRound(UUID roundId) {
		return jdbc.sql("SELECT " + PLAN_COLS + " FROM task_plan_revisions p WHERE p.round_id = :r ORDER BY p.revision_no")
				.param("r", roundId).query(PLAN_MAPPER).list();
	}

	public Optional<Plan> plan(UUID planId) {
		return jdbc.sql("SELECT " + PLAN_COLS + " FROM task_plan_revisions p WHERE p.id = :id").param("id", planId)
				.query(PLAN_MAPPER).optional();
	}

	public List<Sub> submissionsOfRound(UUID roundId) {
		return jdbc.sql(SUB_SELECT + " WHERE s.round_id = :r ORDER BY s.submission_no").param("r", roundId)
				.query(SUB_MAPPER).list();
	}

	public Optional<Sub> submission(UUID submissionId) {
		return jdbc.sql(SUB_SELECT + " WHERE s.id = :id").param("id", submissionId).query(SUB_MAPPER).optional();
	}

	public Optional<Rev> reviewOf(UUID submissionId) {
		return jdbc.sql("""
				SELECT id, submission_id, decision, reviewer_id, issue_description, reviewed_at
				  FROM task_result_reviews WHERE submission_id = :s
				""").param("s", submissionId).query((rs, i) -> new Rev(J.uuid(rs, "id"), J.uuid(rs, "submission_id"),
				rs.getString("decision"), J.uuid(rs, "reviewer_id"), rs.getString("issue_description"),
				J.inst(rs, "reviewed_at"))).optional();
	}

	// ------------------------------------------------------------------ đánh giá đúng hạn (spec 4.1)
	/**
	 * @param deadline  deadline của vòng (bản hiệu lực, hoặc bản chụp tại giờ chốt)
	 * @param closesAt  giờ chốt của kỳ chứa vòng
	 * @param cutoffView true = nhìn "tại giờ chốt": bỏ qua mọi quyết định duyệt (chỉ có sau giờ chốt)
	 */
	public static Eval eval(Ctx c, Instant deadline, Instant now, Instant closesAt, boolean cutoffView) {
		if (deadline == null || (!c.committed() && !cutoffView)) {
			return new Eval("TODO", "NOT_DUE", "Chưa đến hạn", false, false, null);
		}
		Sub sub = c.sub();
		if (cutoffView && sub != null && sub.submittedAt().isAfter(closesAt)) {
			sub = null;
		}
		Rev rev = cutoffView ? null : c.rev();
		boolean accepted = !cutoffView && "ACCEPTED".equals(c.round().state());
		String status;
		if (accepted) {
			status = "DONE";
		}
		else if (rev != null && "FIX_REQUIRED".equals(rev.decision())) {
			status = "FIX_REQUIRED";
		}
		else if (rev != null && "UPDATE_REQUIRED".equals(rev.decision())) {
			status = "UPDATE_REQUIRED";
		}
		else if (sub != null) {
			status = "SUBMITTED";
		}
		else if (c.round().workStartedAt() != null) {
			status = "IN_PROGRESS";
		}
		else {
			status = "TODO";
		}
		String timeliness;
		boolean late;
		if (rev != null && !"ACCEPTED".equals(rev.decision())) {
			timeliness = "NEEDS_REWORK";
			late = true;
		}
		else if (sub != null) {
			boolean onTime = !sub.submittedAt().isAfter(sub.planDeadline() != null ? sub.planDeadline() : deadline);
			if (accepted) {
				timeliness = onTime ? "ON_TIME_ACCEPTED" : "LATE_ACCEPTED";
			}
			else {
				timeliness = onTime ? "ON_TIME_PENDING_REVIEW" : "LATE_PENDING_REVIEW";
			}
			late = !onTime;
		}
		else if (now.isAfter(deadline)) {
			timeliness = "OVERDUE_NO_RESULT";
			late = true;
		}
		else {
			timeliness = "NOT_DUE";
			late = false;
		}
		boolean needs = false;
		String reason = null;
		if (!cutoffView && now.isAfter(closesAt) && !List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state())) {
			if (sub == null) {
				needs = true;
				reason = "NOT_SUBMITTED";
			}
			else if (rev != null && !"ACCEPTED".equals(rev.decision())) {
				needs = true;
				reason = rev.decision();
			}
		}
		return new Eval(status, timeliness, timelinessLabel(timeliness), late, needs, reason);
	}

	public static String timelinessLabel(String t) {
		return switch (t) {
			case "NOT_DUE" -> "Chưa đến hạn";
			case "OVERDUE_NO_RESULT" -> "Quá hạn — chưa có kết quả";
			case "ON_TIME_PENDING_REVIEW" -> "Nộp đúng hạn — chờ TL duyệt";
			case "ON_TIME_ACCEPTED" -> "Hoàn thành đúng hạn";
			case "LATE_PENDING_REVIEW" -> "Nộp trễ — chờ TL duyệt";
			case "LATE_ACCEPTED" -> "Hoàn thành trễ hạn";
			case "NEEDS_REWORK" -> "Cần sửa/cập nhật";
			default -> t;
		};
	}
}
