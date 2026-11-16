package app.hanare.points;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.PointDtos.ReverseSettlementRequest;
import app.hanare.points.PointDtos.ReverseSettlementResponse;
import app.hanare.points.TaskReader.BudgetFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.task.TaskAcceptedHook.AcceptedSubmission;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi sổ điểm (append-only): đúng một khoản {@code AWARD} cho người có quyền nhận hiện tại khi TL chấp nhận kết quả,
 * và đảo khoản (REVERSAL, đúng một lần/khoản). Ledger chỉ được ghi từ hai đường này (§K).
 */
@Service
public class PointLedgerService {

	/** Kết quả ghi điểm; {@code created=false} nghĩa là đã có khoản hiệu lực (gọi lặp, không ghi thêm). */
	public record AwardResult(UUID budgetId, UUID settlementId, UUID ledgerEntryId, UUID recipientId, long units,
			boolean created) {
	}

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public PointLedgerService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, AuditWriter audit,
			NotificationService notifications, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
	}

	/**
	 * Gọi trong transaction duyệt (sau khi lần nộp đã ACCEPTED). Idempotent theo budget: đã có settlement chưa thu hồi ->
	 * trả khoản hiện có, không ghi thêm. Task không có quỹ -> rỗng.
	 *
	 * @param contributionId contribution CONFIRMED của work hỗ trợ (nếu người nhận là Helper), hoặc null
	 */
	@Transactional
	public Optional<AwardResult> awardOnAccepted(AcceptedSubmission a, UUID contributionId, boolean viaSupport) {
		Optional<UUID> budgetId = jdbc.sql("SELECT id FROM task_point_budgets WHERE task_id = :t AND state = 'FUNDED' FOR UPDATE")
				.param("t", a.taskId()).query(UUID.class).optional();
		if (budgetId.isEmpty()) {
			return Optional.empty();
		}
		Optional<AwardResult> existing = jdbc.sql("""
				SELECT s.id settlement_id, l.id ledger_id, s.recipient_id, l.delta_units
				  FROM task_point_settlements s
				  JOIN point_ledger_entries l ON l.settlement_id = s.id AND l.entry_type = 'AWARD'
				 WHERE s.budget_id = :b AND s.revoked_at IS NULL
				""").param("b", budgetId.get())
				.query((rs, i) -> new AwardResult(budgetId.get(), rs.getObject("settlement_id", UUID.class),
						rs.getObject("ledger_id", UUID.class), rs.getObject("recipient_id", UUID.class),
						rs.getLong("delta_units"), false))
				.optional();
		if (existing.isPresent()) {
			return existing;
		}
		BudgetFacts b = tasks.budget(a.taskId()).orElseThrow();
		if (b.recipientId().equals(a.reviewerId())) {
			throw Err.forbidden("SELF_ACTION_FORBIDDEN", "Không ai được duyệt khoản điểm do chính mình nhận.");
		}
		Instant at = a.reviewedAt() != null ? a.reviewedAt() : clock.instant();
		UUID settlementId = jdbc.sql("""
				INSERT INTO task_point_settlements (project_id, budget_id, assignment_id, recipient_id,
				    accepted_submission_id, contribution_id, approved_by, approved_at)
				VALUES (:p, :b, :a, :r, :s, :c, :by, :at) RETURNING id
				""").param("p", a.projectId()).param("b", b.budgetId()).param("a", b.assignmentId())
				.param("r", b.recipientId()).param("s", a.submissionId()).param("c", contributionId)
				.param("by", a.reviewerId()).param("at", DbTime.toDb(at)).query(UUID.class).single();
		UUID ledgerId = insertLedger(a.projectId(), b.budgetId(), settlementId, b.recipientId(), b.units(), "AWARD", null,
				"award:" + settlementId, at);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("taskId", a.taskId());
		after.put("submissionId", a.submissionId());
		after.put("recipientId", b.recipientId());
		after.put("units", b.units());
		audit.log(a.projectId(), a.reviewerId(), "POINT_SETTLEMENT", settlementId, "POINTS_AWARDED", null, after);
		String title = lookups.taskTitle(a.taskId());
		boolean helper = viaSupport || b.assignmentWorkId() != null;
		notifications.publish(new NotificationEvent(a.projectId(), "POINTS_AWARDED", "LEDGER_ENTRY", ledgerId, null,
				"points:award:" + ledgerId, List.of(b.recipientId()), "Điểm đã được ghi nhận",
				"Đã ghi nhận " + PointMath.deltaLabel(b.units()) + " điểm % từ task " + title
						+ (helper ? " (đóng góp hỗ trợ đã được công nhận)." : "."),
				Map.of("taskId", a.taskId().toString(), "ledgerEntryId", ledgerId.toString())));
		return Optional.of(new AwardResult(b.budgetId(), settlementId, ledgerId, b.recipientId(), b.units(), true));
	}

	private UUID insertLedger(UUID projectId, UUID budgetId, UUID settlementId, UUID recipientId, long delta, String type,
			UUID reversesId, String key, Instant at) {
		return jdbc.sql("""
				INSERT INTO point_ledger_entries (project_id, budget_id, settlement_id, recipient_id, delta_units,
				    entry_type, reverses_entry_id, idempotency_key, created_at)
				VALUES (:p, :b, :s, :r, :d, :t, :rev, :k, :at) RETURNING id
				""").param("p", projectId).param("b", budgetId).param("s", settlementId).param("r", recipientId)
				.param("d", delta).param("t", type).param("rev", reversesId).param("k", key).param("at", DbTime.toDb(at))
				.query(UUID.class).single();
	}

	// ------------------------------------------------------------------------------------------------ đảo khoản

	private record SettlementRow(UUID id, UUID projectId, UUID budgetId, UUID taskId, long units, UUID assignmentId,
			UUID recipientId, UUID submissionId, UUID contributionId, Instant revokedAt, UUID originMemberId,
			UUID independentApproverId) {
	}

	@Transactional
	public ReverseSettlementResponse reverse(UUID settlementId, CurrentUser user, String idempotencyKey,
			ReverseSettlementRequest req) {
		IdempotencyKey.require(idempotencyKey);
		if (req == null || req.reason() == null || req.reason().trim().length() < 10 || req.reason().length() > 1000) {
			throw ApiException.validation("reason", "Lý do phải dài 10–1000 ký tự.");
		}
		SettlementRow s = loadSettlement(settlementId)
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy khoản điểm."));
		GroupMembership m = access.requireMember(s.projectId(), user);
		jdbc.sql("SELECT id FROM task_point_budgets WHERE id = :b FOR UPDATE").param("b", s.budgetId()).query(UUID.class)
				.single();
		s = loadSettlement(settlementId).orElseThrow();
		if (s.recipientId().equals(user.id())) {
			throw Err.forbidden("SELF_ACTION_FORBIDDEN", "Không ai được đảo khoản điểm do chính mình nhận.");
		}
		boolean recipientIsLead = access.activeLeaderId(s.projectId()).map(s.recipientId()::equals).orElse(false);
		boolean authorized = (m.isLeader() && !recipientIsLead)
				|| (recipientIsLead && user.id().equals(s.independentApproverId()));
		if (!authorized) {
			throw Err.forbidden("NOT_AUTHORIZED_REVIEWER", "Bạn không phải người duyệt của khoản điểm này.");
		}
		if (s.revokedAt() != null) {
			throw Err.conflict("SETTLEMENT_ALREADY_REVOKED", "Khoản điểm này đã được đảo trước đó.");
		}
		UUID reissue = req.reissueRecipientUserId();
		if (reissue != null) {
			if (reissue.equals(user.id())) {
				throw Err.forbidden("SELF_ACTION_FORBIDDEN", "Không ai được duyệt khoản điểm do chính mình nhận.");
			}
			Set<UUID> valid = new LinkedHashSet<>(jdbc.sql("""
					SELECT x FROM (
					  SELECT ts.submitted_by x FROM task_submissions ts WHERE ts.id = :sub
					  UNION SELECT p.assignee_id FROM task_submissions ts JOIN task_plan_revisions p ON p.id = ts.plan_revision_id
					         WHERE ts.id = :sub
					  UNION SELECT :origin) q WHERE x IS NOT NULL
					""").param("sub", s.submissionId()).param("origin", s.originMemberId()).query(UUID.class).list());
			if (!valid.contains(reissue) || !access.isActiveMember(s.projectId(), reissue)) {
				throw ApiException.validation("reissueRecipientUserId",
						"Người nhận lại phải là người thực hiện hợp lệ của bản đã duyệt.");
			}
		}
		Instant now = clock.instant();
		UUID awardId = jdbc.sql("SELECT id FROM point_ledger_entries WHERE settlement_id = :s AND entry_type = 'AWARD'")
				.param("s", s.id()).query(UUID.class).single();
		UUID reversalId = insertLedger(s.projectId(), s.budgetId(), s.id(), s.recipientId(), -s.units(), "REVERSAL", awardId,
				"reversal:" + awardId, now);
		jdbc.sql("""
				UPDATE task_point_settlements SET revoked_at = :at, revoked_by = :by, revoke_reason = :reason WHERE id = :id
				""").param("at", DbTime.toDb(now)).param("by", user.id()).param("reason", req.reason().trim())
				.param("id", s.id()).update();
		UUID newSettlementId = null;
		UUID newLedgerId = null;
		if (reissue != null) {
			UUID currentAssignment = s.assignmentId();
			UUID contribution = s.contributionId();
			BudgetFacts b = tasks.budget(s.taskId()).orElseThrow();
			if (!b.recipientId().equals(reissue)) {
				jdbc.sql("UPDATE task_point_assignments SET status = 'SUPERSEDED' WHERE id = :id").param("id", b.assignmentId())
						.update();
				currentAssignment = jdbc.sql("""
						INSERT INTO task_point_assignments (project_id, budget_id, recipient_id, previous_assignment_id,
						    confirmed_by, agreed_at)
						VALUES (:p, :b, :r, :prev, :c, :at) RETURNING id
						""").param("p", s.projectId()).param("b", s.budgetId()).param("r", reissue)
						.param("prev", b.assignmentId()).param("c", user.id()).param("at", DbTime.toDb(now))
						.query(UUID.class).single();
				contribution = null;
			}
			else if (b.assignmentWorkId() == null) {
				contribution = null;
			}
			newSettlementId = jdbc.sql("""
					INSERT INTO task_point_settlements (project_id, budget_id, assignment_id, recipient_id,
					    accepted_submission_id, contribution_id, approved_by, approved_at)
					VALUES (:p, :b, :a, :r, :s, :c, :by, :at) RETURNING id
					""").param("p", s.projectId()).param("b", s.budgetId()).param("a", currentAssignment)
					.param("r", reissue).param("s", s.submissionId()).param("c", contribution).param("by", user.id())
					.param("at", DbTime.toDb(now)).query(UUID.class).single();
			newLedgerId = insertLedger(s.projectId(), s.budgetId(), newSettlementId, reissue, s.units(), "AWARD", null,
					"award:" + newSettlementId, now);
		}
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("reason", req.reason().trim());
		after.put("reversalEntryId", reversalId);
		after.put("newSettlementId", newSettlementId);
		audit.log(s.projectId(), user.id(), "POINT_SETTLEMENT", s.id(), "POINTS_REVERSED", null, after);
		Set<UUID> notify = new LinkedHashSet<>();
		notify.add(s.recipientId());
		if (reissue != null) {
			notify.add(reissue);
		}
		notify.add(s.originMemberId());
		notify.remove(user.id());
		String title = lookups.taskTitle(s.taskId());
		notifications.publish(new NotificationEvent(s.projectId(), "POINTS_ADJUSTED", "LEDGER_ENTRY", reversalId, null,
				"points:reversal:" + reversalId, List.copyOf(notify), "Điểm đã được hiệu chỉnh",
				"TL đã hiệu chỉnh khoản điểm của task " + title + " (" + PointMath.deltaLabel(-s.units())
						+ " điểm %). Xem chi tiết lý do trong sổ điểm.",
				Map.of("taskId", s.taskId().toString(), "ledgerEntryId", reversalId.toString())));
		return new ReverseSettlementResponse(s.id(), reversalId, newSettlementId, newLedgerId,
				tasks.summary(s.projectId(), s.taskId()));
	}

	private Optional<SettlementRow> loadSettlement(UUID settlementId) {
		return jdbc.sql("""
				SELECT s.id, s.project_id, s.budget_id, b.task_id, b.point_units, s.assignment_id, s.recipient_id,
				       s.accepted_submission_id, s.contribution_id, s.revoked_at, mb.member_id origin_member_id,
				       t.independent_approver_id
				  FROM task_point_settlements s
				  JOIN task_point_budgets b ON b.id = s.budget_id
				  JOIN member_point_budgets mb ON mb.id = b.origin_member_budget_id
				  JOIN tasks t ON t.id = b.task_id
				 WHERE s.id = :id
				""").param("id", settlementId)
				.query((rs, i) -> new SettlementRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
						rs.getObject("budget_id", UUID.class), rs.getObject("task_id", UUID.class), rs.getLong("point_units"),
						rs.getObject("assignment_id", UUID.class), rs.getObject("recipient_id", UUID.class),
						rs.getObject("accepted_submission_id", UUID.class), rs.getObject("contribution_id", UUID.class),
						DbTime.instant(rs, "revoked_at"), rs.getObject("origin_member_id", UUID.class),
						rs.getObject("independent_approver_id", UUID.class)))
				.optional();
	}
}
