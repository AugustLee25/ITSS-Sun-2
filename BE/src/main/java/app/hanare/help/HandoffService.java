package app.hanare.help;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.points.Err;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.BudgetFacts;
import app.hanare.points.TaskReader.SubmissionFacts;
import app.hanare.points.TaskReader.TaskFacts;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tác động lên task khi bàn giao hỗ trợ (spec 12.8.4): revision hiệu lực {@code TL_SUPPORT_HANDOFF}, đổi người có quyền
 * nhận điểm (assignment mới, cũ → SUPERSEDED) và consume/giải phóng reservation. Ghi bằng SQL của gói help, giữ nguyên
 * bất biến DB; luôn gọi trong transaction của thao tác nghiệp vụ.
 */
@Component
public class HandoffService {

	private final JdbcClient jdbc;
	private final TaskReader tasks;

	public HandoffService(JdbcClient jdbc, TaskReader tasks) {
		this.jdbc = jdbc;
		this.tasks = tasks;
	}

	/**
	 * Task có thể nhờ làm thay không: chưa được chấp nhận, đã có kế hoạch hiệu lực, không còn bản nộp chờ TL duyệt,
	 * không có bản sửa kế hoạch đang xử lý (chỉ chưa nộp hoặc đã bị yêu cầu sửa/cập nhật).
	 */
	public void requireTransferable(TaskFacts f) {
		if (f.archived()) {
			throw ApiException.notFound("Không tìm thấy task.");
		}
		Optional<SubmissionFacts> sub = tasks.latestSubmission(f.roundId());
		if ("ACCEPTED".equals(f.roundState()) || sub.map(s -> "ACCEPTED".equals(s.decision())).orElse(false)) {
			throw Err.conflict("TASK_ALREADY_DONE", "Task đã được chấp nhận, không thể nhờ làm thay.");
		}
		if (!f.hasEffectivePlan()) {
			throw Err.conflict("TASK_NOT_TRANSFERABLE", "Task chưa có kế hoạch được duyệt nên chưa thể nhờ làm thay.");
		}
		if (sub.map(s -> s.decision() == null).orElse(false)) {
			throw Err.conflict("TASK_AWAITING_REVIEW", "Task có kết quả đang chờ TL duyệt; hãy kiểm tra kết quả đó trước.");
		}
		if (tasks.inFlightRevision(f.roundId()).isPresent()) {
			throw Err.conflict("TASK_NOT_TRANSFERABLE", "Task đang có bản sửa kế hoạch chưa được xử lý.");
		}
	}

	/**
	 * Tạo revision hiệu lực mới {@code TL_SUPPORT_HANDOFF} của vòng hiện tại (cùng phạm vi/deadline/độ khó, người thực hiện
	 * mới) và đặt làm {@code effective_plan_id}. Snapshot kỳ đã chốt không bị đụng tới.
	 */
	public UUID handoffRevision(TaskFacts f, UUID newAssignee, UUID byUser, Instant now, String note) {
		UUID revisionId = jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, base_revision_id, proposed_cycle_id,
				    assignee_id, title, description, expected_output, start_at, deadline, difficulty, status, created_by,
				    submitted_by, submitted_at, reviewed_by, reviewed_at, review_note, approval_source)
				SELECT p.project_id, p.round_id,
				       (SELECT MAX(x.revision_no) + 1 FROM task_plan_revisions x WHERE x.round_id = p.round_id),
				       p.id, p.proposed_cycle_id, :assignee, p.title, p.description, p.expected_output, p.start_at,
				       p.deadline, p.difficulty, 'APPROVED', :by, :by, :at, :by, :at, :note, 'TL_SUPPORT_HANDOFF'
				  FROM task_plan_revisions p WHERE p.id = :base
				RETURNING id
				""").param("assignee", newAssignee).param("by", byUser).param("at", DbTime.toDb(now)).param("note", note)
				.param("base", f.effectivePlanId()).query(UUID.class).single();
		jdbc.sql("UPDATE task_review_rounds SET effective_plan_id = :plan, version = version + 1 WHERE id = :r")
				.param("plan", revisionId).param("r", f.roundId()).update();
		return revisionId;
	}

	/** Chuyển quyền nhận 100% P_t: assignment CURRENT cũ → SUPERSEDED, tạo assignment mới cho {@code newRecipient}. */
	public UUID swapAssignment(UUID projectId, BudgetFacts b, UUID newRecipient, UUID helpRequestId, UUID workId,
			UUID confirmedBy, Instant now) {
		jdbc.sql("UPDATE task_point_assignments SET status = 'SUPERSEDED' WHERE id = :id").param("id", b.assignmentId())
				.update();
		return jdbc.sql("""
				INSERT INTO task_point_assignments (project_id, budget_id, recipient_id, previous_assignment_id,
				    help_request_id, support_work_id, confirmed_by, agreed_at)
				VALUES (:p, :b, :r, :prev, :h, :w, :c, :at) RETURNING id
				""").param("p", projectId).param("b", b.budgetId()).param("r", newRecipient).param("prev", b.assignmentId())
				.param("h", helpRequestId).param("w", workId).param("c", confirmedBy).param("at", DbTime.toDb(now))
				.query(UUID.class).single();
	}
}
