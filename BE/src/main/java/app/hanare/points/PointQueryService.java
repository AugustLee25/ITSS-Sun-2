package app.hanare.points;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.points.PointDtos.ContributionDto;
import app.hanare.points.PointDtos.GroupPointsSummary;
import app.hanare.points.PointDtos.LedgerEntryDetail;
import app.hanare.points.PointDtos.MyPointsResponse;
import app.hanare.points.PointDtos.PendingTransfer;
import app.hanare.points.PointDtos.PointBreakdownCount;
import app.hanare.points.PointDtos.PointHistoryItem;
import app.hanare.points.PointDtos.PointTaskRow;
import app.hanare.points.PointDtos.SettlementInfo;
import app.hanare.points.PointDtos.SimulatePointsRequest;
import app.hanare.points.PointDtos.SimulatePointsResponse;
import app.hanare.points.PointDtos.SimulationCandidate;
import app.hanare.points.PointDtos.SimulationCandidatesResponse;
import app.hanare.points.PointDtos.SimulationStep;
import app.hanare.points.PointDtos.TaskSubmissionDto;
import app.hanare.points.PointPolicyService.PolicyRow;
import app.hanare.points.Refs.PageOf;
import app.hanare.points.Refs.TaskRef;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Điểm của tôi, danh sách task có điểm, lịch sử, mô phỏng, quỹ nhóm (read-only; không ghi DB). */
@Service
@Transactional(readOnly = true)
public class PointQueryService {

	private static final Set<String> STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "AWAITING_REVIEW", "NEEDS_REVISION",
			"AWARDED", "TRANSFERRED_OUT");
	private static final Set<String> SOURCES = Set.of("OWN", "TAKEN_OVER");

	private record BudgetRow(UUID budgetId, UUID taskId, long units, String difficulty, UUID originId, UUID recipientId,
			boolean held, boolean awarded, UUID helpRequestId, UUID workId) {
	}

	private record OpenRequest(UUID id, UUID taskId, UUID beneficiaryId, UUID approverId, UUID createdBy) {
	}

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final EvidenceViews views;
	private final PointPolicyService policies;

	public PointQueryService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, EvidenceViews views,
			PointPolicyService policies) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.views = views;
		this.policies = policies;
	}

	// ------------------------------------------------------------------------------------------------ nguồn dữ liệu

	private List<BudgetRow> budgets(UUID projectId) {
		return jdbc.sql("""
				SELECT b.id budget_id, b.task_id, b.point_units, b.difficulty_at_lock, mb.member_id origin_id, a.recipient_id,
				       EXISTS (SELECT 1 FROM task_point_reservations r WHERE r.budget_id = b.id AND r.state = 'HELD') held,
				       EXISTS (SELECT 1 FROM task_point_settlements s WHERE s.budget_id = b.id AND s.revoked_at IS NULL) awarded,
				       COALESCE(a.help_request_id, (SELECT h.id FROM help_requests h WHERE h.task_id = b.task_id
				                                     AND h.status = 'OPEN' LIMIT 1)) help_request_id,
				       a.support_work_id
				  FROM task_point_budgets b
				  JOIN member_point_budgets mb ON mb.id = b.origin_member_budget_id
				  JOIN task_point_assignments a ON a.budget_id = b.id AND a.status = 'CURRENT'
				 WHERE b.project_id = :p AND b.state = 'FUNDED'
				 ORDER BY b.locked_at, b.id
				""").param("p", projectId)
				.query((rs, i) -> new BudgetRow(rs.getObject("budget_id", UUID.class), rs.getObject("task_id", UUID.class),
						rs.getLong("point_units"), rs.getString("difficulty_at_lock"), rs.getObject("origin_id", UUID.class),
						rs.getObject("recipient_id", UUID.class), rs.getBoolean("held"), rs.getBoolean("awarded"),
						rs.getObject("help_request_id", UUID.class), rs.getObject("support_work_id", UUID.class)))
				.list();
	}

	private long confirmed(UUID projectId, UUID userId) {
		return jdbc.sql("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p AND recipient_id = :u")
				.param("p", projectId).param("u", userId).query(Long.class).single();
	}

	private Optional<long[]> myBudget(UUID policyId, UUID userId) {
		return jdbc.sql("SELECT initial_units, reserve_units FROM member_point_budgets WHERE policy_id = :pol AND member_id = :u")
				.param("pol", policyId).param("u", userId)
				.query((rs, i) -> new long[] { rs.getLong("initial_units"), rs.getLong("reserve_units") }).optional();
	}

	private static long sum(List<BudgetRow> rows) {
		return rows.stream().mapToLong(BudgetRow::units).sum();
	}

	// ------------------------------------------------------------------------------------------------ GET points/me

	public MyPointsResponse me(UUID projectId, CurrentUser user) {
		access.requireMember(projectId, user);
		Optional<PolicyRow> policy = policies.activePolicy(projectId);
		PointAmount zero = PointAmount.of(0);
		if (policy.isEmpty()) {
			return new MyPointsResponse(false, PointPolicyService.NO_POLICY_LABEL, null, null, zero, zero, zero,
					new PointBreakdownCount(zero, 0), new PointBreakdownCount(zero, 0), new PendingTransfer(zero, 0), zero,
					null);
		}
		UUID me = user.id();
		List<BudgetRow> all = budgets(projectId);
		List<BudgetRow> mine = all.stream().filter(b -> b.recipientId().equals(me)).toList();
		List<BudgetRow> out = all.stream().filter(b -> b.originId().equals(me) && !b.recipientId().equals(me)).toList();
		List<BudgetRow> in = all.stream().filter(b -> !b.originId().equals(me) && b.recipientId().equals(me)).toList();
		List<BudgetRow> held = mine.stream().filter(b -> b.originId().equals(me) && b.held()).toList();
		long allocated = sum(mine);
		long confirmed = confirmed(projectId, me);
		long pending = Math.max(0, allocated - confirmed);
		Optional<long[]> mb = myBudget(policy.get().id(), me);
		Long initial = mb.map(a -> a[0]).orElse(null);
		long reserve = mb.map(a -> a[1]).orElse(0L);
		int openRequests = (int) held.stream().filter(b -> b.helpRequestId() != null).count();
		return new MyPointsResponse(true, null, policy.get().memberCount(), PointAmount.ofOrNull(initial),
				PointAmount.of(confirmed), PointAmount.of(pending), PointAmount.of(allocated),
				new PointBreakdownCount(PointAmount.of(sum(out)), out.size()),
				new PointBreakdownCount(PointAmount.of(sum(in)), in.size()),
				new PendingTransfer(PointAmount.of(sum(held)), openRequests), PointAmount.of(reserve),
				PointMath.ratioLabel(confirmed, initial));
	}

	// ------------------------------------------------------------------------------------------------ GET points/me/tasks

	public PageOf<PointTaskRow> myTasks(UUID projectId, CurrentUser user, String status, String source, Integer page,
			Integer size) {
		access.requireMember(projectId, user);
		int[] ps = Refs.pageArgs(page, size);
		if (status != null && !STATUSES.contains(status)) {
			throw ApiException.validation("status", "Giá trị không hợp lệ.");
		}
		if (source != null && !SOURCES.contains(source)) {
			throw ApiException.validation("source", "Giá trị không hợp lệ.");
		}
		UUID me = user.id();
		List<PointTaskRow> rows = new ArrayList<>();
		for (BudgetRow b : budgets(projectId)) {
			if (!b.originId().equals(me) && !b.recipientId().equals(me)) {
				continue;
			}
			TaskFacts f = tasks.facts(projectId, b.taskId()).orElse(null);
			if (f == null) {
				continue;
			}
			boolean transferredOut = !b.recipientId().equals(me);
			String st = transferredOut ? "TRANSFERRED_OUT"
					: tasks.pointStatus(f, tasks.budget(b.taskId()).orElse(null), tasks.latestSubmission(f.roundId()).orElse(null));
			String src = b.recipientId().equals(me) && !b.originId().equals(me) ? "TAKEN_OVER" : "OWN";
			if ((status != null && !status.equals(st)) || (source != null && !source.equals(src))) {
				continue;
			}
			rows.add(new PointTaskRow(b.taskId(), f.title(), b.difficulty(), PointAmount.of(b.units()),
					lookups.user(b.originId()), lookups.user(b.recipientId()), src, lookups.cycle(f.cycleId()), f.deadline(), st,
					TaskReader.pointStatusLabel(st), transferredOut ? lookups.user(b.recipientId()) : null, b.held(),
					b.helpRequestId(), b.workId()));
		}
		rows.sort(Comparator.comparing(PointTaskRow::deadline, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(PointTaskRow::title));
		int from = Math.min(ps[0] * ps[1], rows.size());
		int to = Math.min(from + ps[1], rows.size());
		return PageOf.of(rows.subList(from, to), ps[0], ps[1], rows.size());
	}

	// ------------------------------------------------------------------------------------------------ GET points/me/ledger

	private record EventRow(UUID id, String grp, String kind, Instant occurredAt, UUID budgetId, UUID taskId, long units,
			Long delta, Long running, UUID originId, UUID otherUser, UUID approvedBy, String reason, UUID workId,
			UUID ledgerId, UUID recipientId) {
	}

	private static final String EVENTS_SQL = """
			WITH me_ledger AS (
			  SELECT l.id, l.budget_id, l.entry_type, l.delta_units, l.created_at, l.settlement_id, l.recipient_id,
			         CAST(SUM(l.delta_units) OVER (ORDER BY l.created_at, l.id) AS bigint) running
			    FROM point_ledger_entries l WHERE l.project_id = :p AND l.recipient_id = :u
			), events AS (
			  SELECT id, 'POINTS_RECORDED' grp, entry_type kind, created_at occurred_at, budget_id, delta_units delta,
			         running, settlement_id, id ledger_id, CAST(NULL AS uuid) other_user, recipient_id
			    FROM me_ledger
			  UNION ALL
			  SELECT a.id, 'ASSIGNMENT', 'ASSIGNMENT_OUT', a.created_at, a.budget_id, NULL, NULL, NULL, NULL, a.recipient_id, pa.recipient_id
			    FROM task_point_assignments a JOIN task_point_assignments pa ON pa.id = a.previous_assignment_id
			   WHERE a.project_id = :p AND pa.recipient_id = :u AND a.recipient_id <> :u
			  UNION ALL
			  SELECT a.id, 'ASSIGNMENT', 'ASSIGNMENT_IN', a.created_at, a.budget_id, NULL, NULL, NULL, NULL, pa.recipient_id, a.recipient_id
			    FROM task_point_assignments a JOIN task_point_assignments pa ON pa.id = a.previous_assignment_id
			   WHERE a.project_id = :p AND a.recipient_id = :u AND pa.recipient_id <> :u
			  UNION ALL
			  SELECT r.id, 'ASSIGNMENT', 'RESERVATION_HELD', r.created_at, r.budget_id, NULL, NULL, NULL, NULL, CAST(NULL AS uuid), h.beneficiary_id
			    FROM task_point_reservations r JOIN help_requests h ON h.id = r.help_request_id
			   WHERE h.project_id = :p AND h.beneficiary_id = :u
			  UNION ALL
			  SELECT CAST(md5(r.id::text || 'released') AS uuid), 'ASSIGNMENT', 'RESERVATION_RELEASED', r.updated_at, r.budget_id,
			         NULL, NULL, NULL, NULL, CAST(NULL AS uuid), h.beneficiary_id
			    FROM task_point_reservations r JOIN help_requests h ON h.id = r.help_request_id
			   WHERE h.project_id = :p AND h.beneficiary_id = :u AND r.state = 'RELEASED'
			)
			""";

	private static final String EVENT_SELECT = EVENTS_SQL + """
			SELECT e.id, e.grp, e.kind, e.occurred_at, e.budget_id, b.task_id, b.point_units, e.delta, e.running,
			       mb.member_id origin_id, e.other_user,
			       CASE WHEN e.kind = 'REVERSAL' THEN s.revoked_by ELSE s.approved_by END approved_by, s.revoke_reason,
			       c.work_id, e.ledger_id, e.recipient_id
			  FROM events e
			  JOIN task_point_budgets b ON b.id = e.budget_id
			  JOIN member_point_budgets mb ON mb.id = b.origin_member_budget_id
			  LEFT JOIN task_point_settlements s ON s.id = e.settlement_id
			  LEFT JOIN contributions c ON c.id = s.contribution_id
			""";

	private EventRow mapEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new EventRow(rs.getObject("id", UUID.class), rs.getString("grp"), rs.getString("kind"),
				DbTime.instant(rs, "occurred_at"), rs.getObject("budget_id", UUID.class), rs.getObject("task_id", UUID.class),
				rs.getLong("point_units"), rs.getObject("delta", Long.class), rs.getObject("running", Long.class),
				rs.getObject("origin_id", UUID.class), rs.getObject("other_user", UUID.class),
				rs.getObject("approved_by", UUID.class), rs.getString("revoke_reason"), rs.getObject("work_id", UUID.class),
				rs.getObject("ledger_id", UUID.class), rs.getObject("recipient_id", UUID.class));
	}

	public PageOf<PointHistoryItem> myLedger(UUID projectId, CurrentUser user, String group, Integer page, Integer size) {
		access.requireMember(projectId, user);
		int[] ps = Refs.pageArgs(page, size);
		String grp = group == null ? "ALL" : group;
		if (!Set.of("ALL", "POINTS_RECORDED", "ASSIGNMENT").contains(grp)) {
			throw ApiException.validation("group", "Giá trị không hợp lệ.");
		}
		long total = jdbc.sql(EVENTS_SQL + " SELECT COUNT(*) FROM events WHERE (:g = 'ALL' OR grp = :g)")
				.param("p", projectId).param("u", user.id()).param("g", grp).query(Long.class).single();
		List<EventRow> rows = jdbc.sql(EVENT_SELECT + """
				 WHERE (:g = 'ALL' OR e.grp = :g)
				 ORDER BY e.occurred_at DESC, e.id LIMIT :size OFFSET :off
				""").param("p", projectId).param("u", user.id()).param("g", grp).param("size", ps[1])
				.param("off", (long) ps[0] * ps[1]).query((rs, i) -> mapEvent(rs)).list();
		List<PointHistoryItem> items = new ArrayList<>();
		for (EventRow e : rows) {
			items.add(toItem(e, user.id()));
		}
		return PageOf.of(items, ps[0], ps[1], total);
	}

	private PointHistoryItem toItem(EventRow e, UUID viewer) {
		String title = lookups.taskTitle(e.taskId());
		TaskRef task = new TaskRef(e.taskId(), title);
		String p = PointMath.label(e.units());
		boolean recorded = "POINTS_RECORDED".equals(e.grp());
		String source = null;
		String label;
		switch (e.kind()) {
			case "AWARD" -> {
				source = e.originId().equals(e.recipientId()) ? "OWN" : "TAKEN_OVER";
				label = "Đã ghi nhận " + PointMath.deltaLabel(e.units()) + " điểm % từ task " + title;
			}
			case "REVERSAL" -> {
				source = e.originId().equals(e.recipientId()) ? "OWN" : "TAKEN_OVER";
				label = "Đã đảo " + PointMath.deltaLabel(-e.units()) + " điểm % của task " + title;
			}
			case "ASSIGNMENT_OUT" -> label = "Quyền nhận " + p + " điểm % của task " + title + " đã chuyển cho "
					+ lookups.userName(e.otherUser()) + " (chưa phải điểm đã ghi)";
			case "ASSIGNMENT_IN" -> label = "Bạn có thể nhận " + p + " điểm % của task " + title
					+ " sau khi TL duyệt kết quả (chưa phải điểm đã ghi)";
			case "RESERVATION_HELD" -> label = "Đang giữ " + p + " điểm % của task " + title
					+ " cho yêu cầu hỗ trợ (đang chờ chuyển)";
			default -> label = "Đã nhả " + p + " điểm % của task " + title + ": quyền nhận không đổi";
		}
		return new PointHistoryItem(e.id(), e.grp(), e.kind(), e.occurredAt(), task,
				recorded && e.delta() != null ? PointAmount.delta(e.delta()) : null,
				recorded && e.running() != null ? PointAmount.of(e.running()) : null, source,
				recorded ? lookups.user(e.approvedBy()) : null, label, "REVERSAL".equals(e.kind()) ? e.reason() : null,
				recorded ? e.ledgerId() : null, e.workId());
	}

	// ------------------------------------------------------------------------------------------------ GET ledger/{id}

	public LedgerEntryDetail ledgerDetail(UUID projectId, UUID entryId, CurrentUser user) {
		GroupMembership m = access.requireMember(projectId, user);
		Optional<UUID> recipient = jdbc.sql("SELECT recipient_id FROM point_ledger_entries WHERE id = :id AND project_id = :p")
				.param("id", entryId).param("p", projectId).query(UUID.class).optional();
		if (recipient.isEmpty()) {
			throw ApiException.notFound("Không tìm thấy khoản điểm.");
		}
		EventRow e = jdbc.sql(EVENT_SELECT + " WHERE e.id = :id AND e.grp = 'POINTS_RECORDED'").param("p", projectId).param("u", recipient.get())
				.param("id", entryId).query((rs, i) -> mapEvent(rs)).single();
		if (!m.isLeader() && !user.id().equals(recipient.get()) && !user.id().equals(e.originId())) {
			throw ApiException.forbidden("Bạn không có quyền xem khoản điểm này.");
		}
		record Settle(UUID id, UUID approvedBy, Instant approvedAt, Instant revokedAt, String reason, UUID submissionId,
				UUID contributionId, UUID workId) {
		}
		Settle s = jdbc.sql("""
				SELECT s.id, s.approved_by, s.approved_at, s.revoked_at, s.revoke_reason, s.accepted_submission_id,
				       s.contribution_id, c.work_id
				  FROM point_ledger_entries l JOIN task_point_settlements s ON s.id = l.settlement_id
				  LEFT JOIN contributions c ON c.id = s.contribution_id WHERE l.id = :id
				""").param("id", entryId)
				.query((rs, i) -> new Settle(rs.getObject("id", UUID.class), rs.getObject("approved_by", UUID.class),
						DbTime.instant(rs, "approved_at"), DbTime.instant(rs, "revoked_at"), rs.getString("revoke_reason"),
						rs.getObject("accepted_submission_id", UUID.class), rs.getObject("contribution_id", UUID.class),
						rs.getObject("work_id", UUID.class)))
				.single();
		TaskSubmissionDto submission = views.submission(s.submissionId()).orElse(null);
		ContributionDto contribution = s.contributionId() == null ? null : views.contribution(s.contributionId()).orElse(null);
		UUID reverses = jdbc.sql("SELECT reverses_entry_id FROM point_ledger_entries WHERE id = :id").param("id", entryId)
				.query(UUID.class).optional().orElse(null);
		UUID reversedBy = jdbc.sql("SELECT id FROM point_ledger_entries WHERE reverses_entry_id = :id").param("id", entryId)
				.query(UUID.class).optional().orElse(null);
		return new LedgerEntryDetail(toItem(e, user.id()), lookups.taskRef(e.taskId()), PointAmount.of(e.units()),
				lookups.user(e.originId()), submission, submission == null ? null : submission.review(), contribution,
				new SettlementInfo(s.id(), lookups.user(s.approvedBy()), s.approvedAt(), s.revokedAt(), s.reason()),
				s.workId(), e.kind(), reverses, reversedBy);
	}

	// ------------------------------------------------------------------------------------------------ mô phỏng

	private PolicyRow requireActive(UUID projectId) {
		return policies.activePolicy(projectId).orElseThrow(() -> Err.conflict("POINT_POLICY_NOT_ACTIVE",
				PointPolicyService.NO_POLICY_LABEL + "."));
	}

	private List<OpenRequest> openRequests(UUID projectId) {
		return jdbc.sql("""
				SELECT id, task_id, beneficiary_id, result_approver_id, created_by FROM help_requests
				 WHERE project_id = :p AND status = 'OPEN'
				""").param("p", projectId)
				.query((rs, i) -> new OpenRequest(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
						rs.getObject("beneficiary_id", UUID.class), rs.getObject("result_approver_id", UUID.class),
						rs.getObject("created_by", UUID.class)))
				.list();
	}

	private record Candidates(List<BudgetRow> transferable, Map<UUID, OpenRequest> takeable, List<BudgetRow> takeRows,
			List<BudgetRow> all) {
	}

	private Candidates candidates(UUID projectId, UUID me) {
		List<BudgetRow> all = budgets(projectId);
		List<BudgetRow> transferable = all.stream().filter(b -> b.recipientId().equals(me) && !b.awarded()).toList();
		Map<UUID, OpenRequest> open = new HashMap<>();
		for (OpenRequest r : openRequests(projectId)) {
			open.put(r.taskId(), r);
		}
		List<BudgetRow> takeRows = new ArrayList<>();
		Map<UUID, OpenRequest> takeable = new HashMap<>();
		for (BudgetRow b : all) {
			OpenRequest r = open.get(b.taskId());
			if (r != null && !b.recipientId().equals(me) && !b.awarded() && !me.equals(r.beneficiaryId())
					&& !me.equals(r.approverId()) && !me.equals(r.createdBy())) {
				takeRows.add(b);
				takeable.put(b.taskId(), r);
			}
		}
		return new Candidates(transferable, takeable, takeRows, all);
	}

	public SimulationCandidatesResponse simulationCandidates(UUID projectId, CurrentUser user) {
		access.requireMember(projectId, user);
		requireActive(projectId);
		Candidates c = candidates(projectId, user.id());
		List<SimulationCandidate> transferable = new ArrayList<>();
		for (BudgetRow b : c.transferable()) {
			UUID helpId = b.helpRequestId();
			transferable.add(new SimulationCandidate(b.taskId(), lookups.taskTitle(b.taskId()), b.difficulty(),
					PointAmount.of(b.units()), lookups.user(b.recipientId()), helpId));
		}
		List<SimulationCandidate> takeable = new ArrayList<>();
		for (BudgetRow b : c.takeRows()) {
			takeable.add(new SimulationCandidate(b.taskId(), lookups.taskTitle(b.taskId()), b.difficulty(),
					PointAmount.of(b.units()), lookups.user(b.recipientId()), c.takeable().get(b.taskId()).id()));
		}
		return new SimulationCandidatesResponse(transferable, takeable);
	}

	public SimulatePointsResponse simulate(UUID projectId, CurrentUser user, SimulatePointsRequest req) {
		access.requireMember(projectId, user);
		List<UUID> out = req == null || req.transferOutTaskIds() == null ? List.of() : req.transferOutTaskIds();
		List<UUID> in = req == null || req.takeOnTaskIds() == null ? List.of() : req.takeOnTaskIds();
		if (out.isEmpty() && in.isEmpty()) {
			throw ApiException.validation("transferOutTaskIds", "Chọn ít nhất một task để mô phỏng.");
		}
		requireActive(projectId);
		UUID me = user.id();
		Candidates c = candidates(projectId, me);
		Map<UUID, BudgetRow> transferable = new HashMap<>();
		c.transferable().forEach(b -> transferable.put(b.taskId(), b));
		Map<UUID, BudgetRow> takeable = new HashMap<>();
		c.takeRows().forEach(b -> takeable.put(b.taskId(), b));
		List<ApiError.FieldError> bad = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < out.size(); i++) {
			UUID id = out.get(i);
			if (id == null || !transferable.containsKey(id) || !seen.add(id)) {
				bad.add(new ApiError.FieldError("transferOutTaskIds[" + i + "]", "Task này không thể mô phỏng chuyển đi."));
			}
		}
		for (int i = 0; i < in.size(); i++) {
			UUID id = in.get(i);
			if (id == null || !takeable.containsKey(id) || !seen.add(id)) {
				bad.add(new ApiError.FieldError("takeOnTaskIds[" + i + "]", "Task này không thể mô phỏng nhận làm thay."));
			}
		}
		if (!bad.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SIMULATION_INVALID_TASK",
					"Có task không hợp lệ để mô phỏng.", bad);
		}
		long start = sum(c.transferable());
		List<SimulationStep> steps = new ArrayList<>();
		steps.add(new SimulationStep("START", "Hiện có thể đạt " + PointMath.label(start) + "%", null, null,
				PointAmount.of(start)));
		long cur = start;
		for (UUID id : out) {
			BudgetRow b = transferable.get(id);
			cur -= b.units();
			steps.add(new SimulationStep("TRANSFER_OUT", "Chuyển task " + PointMath.label(b.units()) + " điểm % → còn "
					+ PointMath.label(cur) + "%", id, PointAmount.delta(-b.units()), PointAmount.of(cur)));
		}
		for (UUID id : in) {
			BudgetRow b = takeable.get(id);
			cur += b.units();
			steps.add(new SimulationStep("TAKE_ON", "Nhận task " + PointMath.label(b.units())
					+ " điểm % → có thể đạt " + PointMath.label(cur) + "%", id, PointAmount.delta(b.units()),
					PointAmount.of(cur)));
		}
		return new SimulatePointsResponse(PointAmount.of(confirmed(projectId, me)), PointAmount.of(start),
				PointAmount.of(cur), steps, "Đây là mô phỏng, không ghi điểm");
	}

	// ------------------------------------------------------------------------------------------------ summary

	public GroupPointsSummary summary(UUID projectId, CurrentUser user) {
		access.requireMember(projectId, user);
		Optional<PolicyRow> policy = policies.activePolicy(projectId);
		PointAmount total = PointAmount.of(PointMath.TOTAL_UNITS);
		PointAmount zero = PointAmount.of(0);
		if (policy.isEmpty()) {
			return new GroupPointsSummary(false, PointPolicyService.NO_POLICY_LABEL, total, zero, zero, zero);
		}
		long confirmed = jdbc.sql("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p")
				.param("p", projectId).query(Long.class).single();
		long reserve = jdbc.sql("SELECT COALESCE(SUM(reserve_units), 0) FROM member_point_budgets WHERE policy_id = :pol")
				.param("pol", policy.get().id()).query(Long.class).single();
		long pending = PointMath.TOTAL_UNITS - confirmed - reserve;
		return new GroupPointsSummary(true, null, total, PointAmount.of(confirmed), PointAmount.of(pending),
				PointAmount.of(reserve));
	}
}
