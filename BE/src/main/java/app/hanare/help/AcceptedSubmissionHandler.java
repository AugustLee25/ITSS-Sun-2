package app.hanare.help;

import app.hanare.common.DbTime;
import app.hanare.help.HelpViews.Work;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.AuditWriter;
import app.hanare.points.Err;
import app.hanare.points.PointLedgerService;
import app.hanare.points.PointLedgerService.AwardResult;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.BudgetFacts;
import app.hanare.task.TaskAcceptedHook;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài {@link TaskAcceptedHook} (BE-B1 gọi trong CÙNG transaction duyệt kết quả): chuyển {@code support_works}/contribution/
 * yêu cầu sang xác nhận và ghi ĐÚNG MỘT khoản {@code AWARD} cho người có quyền nhận hiện tại. Idempotent theo task/lần nộp:
 * gọi lặp không tạo contribution hay khoản điểm thứ hai (DB còn trigger/unique chặn cuối).
 */
@Component
public class AcceptedSubmissionHandler implements TaskAcceptedHook {

	private final JdbcClient jdbc;
	private final HelpViews views;
	private final TaskReader tasks;
	private final PointLedgerService ledger;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public AcceptedSubmissionHandler(JdbcClient jdbc, HelpViews views, TaskReader tasks, PointLedgerService ledger,
			AuditWriter audit, NotificationService notifications, Clock clock) {
		this.jdbc = jdbc;
		this.views = views;
		this.tasks = tasks;
		this.ledger = ledger;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void onSubmissionAccepted(AcceptedSubmission a) {
		Instant at = a.reviewedAt() != null ? a.reviewedAt() : clock.instant();
		// một yêu cầu OPEN đang giữ quỹ task: phải đóng trước khi xét điểm (không giữ hai quyền nhận song song)
		boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM help_requests WHERE task_id = :t AND status = 'OPEN')")
				.param("t", a.taskId()).query(Boolean.class).single();
		if (open) {
			throw Err.conflict("HELP_REQUEST_BLOCKS_REVIEW",
					"Yêu cầu hỗ trợ đang mở giữ quỹ điểm của task; hãy đóng yêu cầu trước khi duyệt.");
		}
		Optional<Work> work = jdbc.sql(HelpViews.WORK_SELECT + " WHERE id = (SELECT ss.work_id FROM support_submissions ss "
				+ "WHERE ss.task_submission_id = :s) FOR UPDATE").param("s", a.submissionId())
				.query((rs, i) -> HelpViews.mapWork(rs)).optional();
		UUID contributionId = null;
		boolean viaSupport = false;
		if (work.isPresent()) {
			Work w = work.get();
			if (a.reviewerId().equals(w.helperId())) {
				throw Err.forbidden("SELF_ACTION_FORBIDDEN", "Helper không được duyệt kết quả của chính mình.");
			}
			UUID supportSubmissionId = jdbc.sql("SELECT id FROM support_submissions WHERE task_submission_id = :s")
					.param("s", a.submissionId()).query(UUID.class).single();
			jdbc.sql("""
					INSERT INTO contributions (project_id, work_id, help_request_id, helper_id)
					VALUES (:p, :w, :h, :u) ON CONFLICT (work_id) DO NOTHING
					""").param("p", w.projectId()).param("w", w.id()).param("h", w.helpRequestId()).param("u", w.helperId())
					.update();
			jdbc.sql("""
					UPDATE contributions SET status = 'CONFIRMED', confirmed_by = :by, confirmed_at = :at,
					       accepted_submission_id = :ss WHERE work_id = :w AND status <> 'CONFIRMED'
					""").param("by", a.reviewerId()).param("at", DbTime.toDb(at)).param("ss", supportSubmissionId)
					.param("w", w.id()).update();
			contributionId = jdbc.sql("SELECT id FROM contributions WHERE work_id = :w").param("w", w.id()).query(UUID.class)
					.single();
			if (!"APPROVED".equals(w.status())) {
				jdbc.sql("UPDATE support_works SET status = 'APPROVED', updated_at = :at, version = version + 1 WHERE id = :id")
						.param("at", DbTime.toDb(at)).param("id", w.id()).update();
				jdbc.sql("UPDATE help_requests SET status = 'COMPLETED', updated_at = :at, version = version + 1 WHERE id = :id")
						.param("at", DbTime.toDb(at)).param("id", w.helpRequestId()).update();
				jdbc.sql("""
						INSERT INTO support_work_events (project_id, work_id, kind, actor_id, occurred_at)
						VALUES (:p, :w, 'APPROVED', :a, :at)
						""").param("p", w.projectId()).param("w", w.id()).param("a", a.reviewerId()).param("at", DbTime.toDb(at))
						.update();
				audit.log(w.projectId(), a.reviewerId(), "SUPPORT_WORK", w.id(), "SUPPORT_WORK_APPROVED", null,
						Map.of("contributionId", contributionId));
			}
			viaSupport = true;
		}
		Optional<BudgetFacts> budget = tasks.budget(a.taskId());
		if (budget.isPresent() && budget.get().assignmentWorkId() != null && contributionId == null) {
			throw Err.conflict("STALE_SUBMISSION",
					"Quyền nhận điểm đã chuyển cho Helper; chỉ kết quả do Helper nộp mới được ghi điểm.");
		}
		Optional<AwardResult> award = ledger.awardOnAccepted(a, contributionId, viaSupport);
		if (award.isEmpty() && viaSupport) {
			// không có phương án điểm: chỉ xác nhận đóng góp (không có khoản điểm để gộp)
			Work w = work.get();
			notifications.publish(new NotificationEvent(w.projectId(), "CONTRIBUTION_CONFIRMED", "SUPPORT_WORK", w.id(), null,
					"work:" + w.id() + ":contribution-confirmed", List.of(w.helperId()), "Đóng góp đã được công nhận",
					"TL đã xác nhận đóng góp hỗ trợ của bạn.", Map.of("workId", w.id().toString())));
		}
	}
}
