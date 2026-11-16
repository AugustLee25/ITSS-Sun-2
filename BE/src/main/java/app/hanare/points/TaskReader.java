package app.hanare.points;

import app.hanare.common.DbTime;
import app.hanare.points.Refs.CycleRef;
import app.hanare.points.Refs.UserRef;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Đọc CHỈ-ĐỌC dữ liệu task/vòng/lần nộp bằng SQL (không gọi service của BE-B1) để support/help/points dựng nghiệp vụ và
 * {@link TaskSummaryView}. Không ghi gì vào bảng task.
 */
@Component("pointsTaskReader")
public class TaskReader {

	/** Vòng mới nhất của task + bản kế hoạch có hiệu lực + kỳ của vòng. */
	public record TaskFacts(UUID taskId, UUID projectId, UUID createdBy, UUID independentApproverId, boolean archived,
			long taskVersion, UUID roundId, int roundNo, String roundState, Instant roundStartedAt, UUID cycleId,
			int cycleSeq, Instant cyclePeriodStart, Instant cycleClosesAt, String cycleStatus, UUID effectivePlanId,
			UUID assigneeId, String title, String description, String expectedOutput, Instant startAt, Instant deadline,
			String difficulty) {

		public boolean hasEffectivePlan() {
			return effectivePlanId != null;
		}
	}

	/** Quỹ điểm của task + người có quyền nhận hiện tại. */
	public record BudgetFacts(UUID budgetId, long units, String difficulty, int weight, String state, UUID originMemberId,
			UUID originMemberBudgetId, UUID assignmentId, UUID recipientId, UUID assignmentHelpRequestId,
			UUID assignmentWorkId, boolean held, boolean awarded) {
	}

	public record SubmissionFacts(UUID id, int no, UUID submittedBy, Instant submittedAt, UUID planRevisionId,
			Instant planDeadline, String decision, String issue) {
	}

	/** Khớp TaskPointsBrief trong api-types.ts. */
	public record TaskPointsBrief(PointAmount amount, String status, UserRef originalAssignee, UserRef currentRecipient,
			boolean heldForHelpRequest, boolean awarded) {
	}

	/** Khớp TaskSummary trong api-types.ts (allowedActions để rỗng: BE-B1 sở hữu phần tính hành động của task). */
	public record TaskSummaryView(UUID id, UUID groupId, String title, String difficulty, UserRef originalAssignee,
			UserRef currentExecutor, CycleRef cycle, UUID roundId, int roundNo, Instant startAt, Instant deadline,
			Instant originalDeadline, boolean committed, String planStatus, String pendingRevisionStatus,
			UUID pendingRevisionId, String taskStatus, String riskStatus, String timeliness, String timelinessLabel,
			boolean isLate, boolean needsCarryover, String carryoverReason, Instant latestSubmittedAt,
			TaskPointsBrief points, boolean hasOpenHelpRequest, long version, List<String> allowedActions) {
	}

	private final JdbcClient jdbc;
	private final Clock clock;
	private final Lookups lookups;

	public TaskReader(JdbcClient jdbc, Clock clock, Lookups lookups) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.lookups = lookups;
	}

	private static final String FACTS_SQL = """
			SELECT t.id task_id, t.project_id, t.created_by, t.independent_approver_id, t.archived_at, t.version task_version,
			       r.id round_id, r.round_no, r.state round_state, r.started_at, r.cycle_id, r.effective_plan_id,
			       c.sequence_no, c.period_start_at, c.closes_at, c.status cycle_status,
			       p.assignee_id, p.description, p.expected_output, p.start_at, p.deadline, p.difficulty,
			       COALESCE(p.title, (SELECT q.title FROM task_plan_revisions q WHERE q.round_id = r.id AND q.title IS NOT NULL
			                           ORDER BY q.revision_no DESC LIMIT 1), 'Task chưa đặt tên') title
			  FROM tasks t
			  JOIN task_review_rounds r ON r.task_id = t.id
			       AND r.round_no = (SELECT MAX(x.round_no) FROM task_review_rounds x WHERE x.task_id = t.id)
			  JOIN checkpoint_cycles c ON c.id = r.cycle_id
			  LEFT JOIN task_plan_revisions p ON p.id = r.effective_plan_id
			""";

	public Optional<TaskFacts> facts(UUID taskId) {
		return jdbc.sql(FACTS_SQL + " WHERE t.id = :t").param("t", taskId).query(this::mapFacts).optional();
	}

	public Optional<TaskFacts> facts(UUID projectId, UUID taskId) {
		return jdbc.sql(FACTS_SQL + " WHERE t.id = :t AND t.project_id = :p").param("t", taskId).param("p", projectId)
				.query(this::mapFacts).optional();
	}

	/** Mọi task chưa lưu trữ của nhóm (vòng mới nhất). */
	public List<TaskFacts> allFacts(UUID projectId) {
		return jdbc.sql(FACTS_SQL + " WHERE t.project_id = :p AND t.archived_at IS NULL ORDER BY t.created_at, t.id")
				.param("p", projectId).query(this::mapFacts).list();
	}

	private TaskFacts mapFacts(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new TaskFacts(rs.getObject("task_id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("created_by", UUID.class), rs.getObject("independent_approver_id", UUID.class),
				rs.getObject("archived_at") != null, rs.getLong("task_version"), rs.getObject("round_id", UUID.class),
				rs.getInt("round_no"), rs.getString("round_state"), DbTime.instant(rs, "started_at"),
				rs.getObject("cycle_id", UUID.class), rs.getInt("sequence_no"), DbTime.instant(rs, "period_start_at"),
				DbTime.instant(rs, "closes_at"), rs.getString("cycle_status"), rs.getObject("effective_plan_id", UUID.class),
				rs.getObject("assignee_id", UUID.class), rs.getString("title"), rs.getString("description"),
				rs.getString("expected_output"), DbTime.instant(rs, "start_at"), DbTime.instant(rs, "deadline"),
				rs.getString("difficulty"));
	}

	/** Người thực hiện hiện tại: bản có hiệu lực của vòng mới nhất; chưa duyệt thì người được giao ở bản mới nhất. */
	public UUID executorOf(TaskFacts f) {
		if (f.assigneeId() != null) {
			return f.assigneeId();
		}
		return jdbc.sql("SELECT assignee_id FROM task_plan_revisions WHERE round_id = :r AND assignee_id IS NOT NULL ORDER BY revision_no DESC LIMIT 1")
				.param("r", f.roundId()).query(UUID.class).optional().orElse(null);
	}

	public Optional<BudgetFacts> budget(UUID taskId) {
		return jdbc.sql("""
				SELECT b.id, b.point_units, b.difficulty_at_lock, b.weight_at_lock, b.state, mb.member_id origin_member_id,
				       b.origin_member_budget_id, a.id assignment_id, a.recipient_id, a.help_request_id, a.support_work_id,
				       EXISTS (SELECT 1 FROM task_point_reservations r WHERE r.budget_id = b.id AND r.state = 'HELD') held,
				       EXISTS (SELECT 1 FROM task_point_settlements s WHERE s.budget_id = b.id AND s.revoked_at IS NULL) awarded
				  FROM task_point_budgets b
				  JOIN member_point_budgets mb ON mb.id = b.origin_member_budget_id
				  JOIN task_point_assignments a ON a.budget_id = b.id AND a.status = 'CURRENT'
				 WHERE b.task_id = :t
				""").param("t", taskId)
				.query((rs, i) -> new BudgetFacts(rs.getObject("id", UUID.class), rs.getLong("point_units"),
						rs.getString("difficulty_at_lock"), rs.getInt("weight_at_lock"), rs.getString("state"),
						rs.getObject("origin_member_id", UUID.class), rs.getObject("origin_member_budget_id", UUID.class),
						rs.getObject("assignment_id", UUID.class), rs.getObject("recipient_id", UUID.class),
						rs.getObject("help_request_id", UUID.class), rs.getObject("support_work_id", UUID.class),
						rs.getBoolean("held"), rs.getBoolean("awarded")))
				.optional();
	}

	public Optional<SubmissionFacts> latestSubmission(UUID roundId) {
		return jdbc.sql("""
				SELECT s.id, s.submission_no, s.submitted_by, s.submitted_at, s.plan_revision_id, p.deadline,
				       rv.decision, rv.issue_description
				  FROM task_submissions s
				  JOIN task_plan_revisions p ON p.id = s.plan_revision_id
				  LEFT JOIN task_result_reviews rv ON rv.submission_id = s.id
				 WHERE s.round_id = :r ORDER BY s.submission_no DESC LIMIT 1
				""").param("r", roundId)
				.query((rs, i) -> new SubmissionFacts(rs.getObject("id", UUID.class), rs.getInt("submission_no"),
						rs.getObject("submitted_by", UUID.class), DbTime.instant(rs, "submitted_at"),
						rs.getObject("plan_revision_id", UUID.class), DbTime.instant(rs, "deadline"),
						rs.getString("decision"), rs.getString("issue_description")))
				.optional();
	}

	/** Có bản kế hoạch chưa APPROVED (đang soạn/chờ duyệt/bị trả) ở vòng này. */
	public Optional<UUID> inFlightRevision(UUID roundId) {
		return jdbc.sql("SELECT id FROM task_plan_revisions WHERE round_id = :r AND status <> 'APPROVED'")
				.param("r", roundId).query(UUID.class).optional();
	}

	public String pendingRevisionStatus(UUID roundId) {
		return jdbc.sql("SELECT status FROM task_plan_revisions WHERE round_id = :r AND status <> 'APPROVED'")
				.param("r", roundId).query(String.class).optional().orElse(null);
	}

	/** Trạng thái thực hiện của task theo vòng mới nhất (TaskStatus). */
	public String taskStatus(TaskFacts f, SubmissionFacts sub) {
		if ("ACCEPTED".equals(f.roundState())) {
			return "DONE";
		}
		if (sub != null) {
			if (sub.decision() == null) {
				return "SUBMITTED";
			}
			if (!"ACCEPTED".equals(sub.decision())) {
				return sub.decision();
			}
			return "DONE";
		}
		return f.roundStartedAt() != null ? "IN_PROGRESS" : "TODO";
	}

	/** Trạng thái điểm của task (PointTaskStatus) KHÔNG xét người xem (không có TRANSFERRED_OUT). */
	public String pointStatus(TaskFacts f, BudgetFacts b, SubmissionFacts sub) {
		if (b != null && b.awarded()) {
			return "AWARDED";
		}
		if ("CARRYOVER_REQUIRED".equals(f.roundState())) {
			return "NEEDS_REVISION";
		}
		if (sub != null) {
			if (sub.decision() == null || "ACCEPTED".equals(sub.decision())) {
				return "AWAITING_REVIEW";
			}
			return "NEEDS_REVISION";
		}
		return f.roundStartedAt() != null ? "IN_PROGRESS" : "NOT_STARTED";
	}

	public static String pointStatusLabel(String status) {
		return switch (status) {
			case "NOT_STARTED" -> "Chưa làm";
			case "IN_PROGRESS" -> "Đang làm";
			case "AWAITING_REVIEW" -> "Chờ duyệt";
			case "NEEDS_REVISION" -> "Cần sửa";
			case "AWARDED" -> "Đã ghi điểm";
			case "TRANSFERRED_OUT" -> "Đã chuyển đi";
			default -> status;
		};
	}

	/** Trả [timeliness, isLate(true/false)] theo §4.1; deadline null -> NOT_DUE. */
	public String[] timeliness(TaskFacts f, SubmissionFacts sub) {
		Instant now = clock.instant();
		if (sub == null) {
			if (f.deadline() == null || !now.isAfter(f.deadline())) {
				return new String[] { "NOT_DUE", "false" };
			}
			return new String[] { "OVERDUE_NO_RESULT", "true" };
		}
		boolean onTime = sub.planDeadline() == null || !sub.submittedAt().isAfter(sub.planDeadline());
		if (sub.decision() == null) {
			return new String[] { onTime ? "ON_TIME_PENDING_REVIEW" : "LATE_PENDING_REVIEW", String.valueOf(!onTime) };
		}
		if ("ACCEPTED".equals(sub.decision())) {
			return new String[] { onTime ? "ON_TIME_ACCEPTED" : "LATE_ACCEPTED", String.valueOf(!onTime) };
		}
		return new String[] { "NEEDS_REWORK", "true" };
	}

	public static String timelinessLabel(String timeliness) {
		return switch (timeliness) {
			case "NOT_DUE" -> "Chưa đến hạn";
			case "OVERDUE_NO_RESULT" -> "Quá hạn — chưa có kết quả";
			case "ON_TIME_PENDING_REVIEW" -> "Nộp đúng hạn — chờ TL duyệt";
			case "ON_TIME_ACCEPTED" -> "Nộp đúng hạn — đã xác nhận";
			case "LATE_PENDING_REVIEW" -> "Nộp trễ — chờ TL duyệt";
			case "LATE_ACCEPTED" -> "Hoàn thành trễ — đã xác nhận";
			default -> "Cần sửa hoặc cập nhật";
		};
	}

	/** Dựng TaskSummary (không kèm allowedActions). */
	public TaskSummaryView summary(UUID projectId, UUID taskId) {
		TaskFacts f = facts(projectId, taskId).orElseThrow(() -> app.hanare.common.ApiException.notFound("Không tìm thấy task."));
		SubmissionFacts sub = latestSubmission(f.roundId()).orElse(null);
		BudgetFacts b = budget(taskId).orElse(null);
		String[] tl = timeliness(f, sub);
		UserRef originalAssignee;
		if (b != null) {
			originalAssignee = lookups.user(b.originMemberId());
		}
		else {
			UUID first = jdbc.sql("""
					SELECT p.assignee_id FROM task_review_rounds r JOIN task_plan_revisions p ON p.id = r.effective_plan_id
					 WHERE r.task_id = :t ORDER BY r.round_no LIMIT 1
					""").param("t", taskId).query(UUID.class).optional().orElse(f.assigneeId());
			originalAssignee = lookups.user(first);
		}
		UserRef executor = lookups.user(f.assigneeId() != null ? f.assigneeId() : originalAssignee.userId());
		Instant originalDeadline = jdbc.sql("""
				SELECT p.deadline FROM task_review_rounds r JOIN task_plan_revisions p ON p.id = r.effective_plan_id
				 WHERE r.task_id = :t ORDER BY r.round_no LIMIT 1
				""").param("t", taskId).query((rs, i) -> DbTime.instant(rs, "deadline")).optional().orElse(null);
		CycleRef cycle = Lookups.cycleOf(f.cycleId(), f.cycleSeq(), f.cyclePeriodStart(), f.cycleClosesAt(),
				jdbc.sql("SELECT timezone FROM projects WHERE id = :p").param("p", projectId).query(String.class).single());
		UUID pendingId = inFlightRevision(f.roundId()).orElse(null);
		String risk = jdbc.sql("""
				SELECT COALESCE(MAX(CASE risk_type WHEN 'BLOCKED' THEN 2 WHEN 'DELAY_RISK' THEN 1 ELSE 0 END), 0)
				  FROM risk_reports WHERE project_id = :p AND status <> 'RESOLVED' AND risk_type <> 'DROPOUT_RISK'
				   AND (task_id = :t OR :t = ANY (affected_task_ids))
				""").param("p", projectId).param("t", taskId).query(Integer.class).single() == 2 ? "BLOCKED" : null;
		if (risk == null) {
			int r = jdbc.sql("""
					SELECT COUNT(*) FROM risk_reports WHERE project_id = :p AND status <> 'RESOLVED'
					   AND risk_type = 'DELAY_RISK' AND (task_id = :t OR :t = ANY (affected_task_ids))
					""").param("p", projectId).param("t", taskId).query(Integer.class).single();
			risk = r > 0 ? "AT_RISK" : "NORMAL";
		}
		boolean openHelp = jdbc.sql("SELECT EXISTS (SELECT 1 FROM help_requests WHERE task_id = :t AND status IN ('OPEN','MATCHED','IN_PROGRESS','SUBMITTED'))")
				.param("t", taskId).query(Boolean.class).single();
		TaskPointsBrief brief = null;
		if (b != null) {
			brief = new TaskPointsBrief(PointAmount.of(b.units()), pointStatus(f, b, sub), lookups.user(b.originMemberId()),
					lookups.user(b.recipientId()), b.held(), b.awarded());
		}
		return new TaskSummaryView(f.taskId(), f.projectId(), f.title(), f.difficulty(), originalAssignee, executor, cycle,
				f.roundId(), f.roundNo(), f.startAt(), f.deadline(), originalDeadline, f.hasEffectivePlan(),
				f.hasEffectivePlan() ? "APPROVED" : jdbc.sql("SELECT COALESCE((SELECT status FROM task_plan_revisions WHERE round_id = :r ORDER BY revision_no DESC LIMIT 1), 'DRAFT')")
						.param("r", f.roundId()).query(String.class).single(),
				pendingRevisionStatus(f.roundId()), pendingId, taskStatus(f, sub), risk, tl[0], timelinessLabel(tl[0]),
				Boolean.parseBoolean(tl[1]), "CARRYOVER_REQUIRED".equals(f.roundState()), null,
				sub == null ? null : sub.submittedAt(), brief, openHelp, f.taskVersion(), List.of());
	}
}
