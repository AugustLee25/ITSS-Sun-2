package app.hanare.points;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.PointDtos.ActivatePointPolicyRequest;
import app.hanare.points.PointDtos.ActivatePointPolicyResponse;
import app.hanare.points.PointDtos.Allocation;
import app.hanare.points.PointDtos.AllocationMember;
import app.hanare.points.PointDtos.AllocationTask;
import app.hanare.points.PointDtos.PointPolicyDto;
import app.hanare.points.PointDtos.PointPolicyPreviewRequest;
import app.hanare.points.PointDtos.PointPolicyPreviewResponse;
import app.hanare.points.PointDtos.PointPolicyResponse;
import app.hanare.points.PointDtos.PolicyIssue;
import app.hanare.points.PointDtos.ReserveInput;
import app.hanare.points.PointDtos.SumCheck;
import app.hanare.points.Refs.UserRef;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phương án điểm (spec 12.8.2–12.8.3): B_i = 100/N (phần dư lớn nhất), R_i dự phòng, P_t = (B_i − R_i) × w / W_i
 * (phần dư lớn nhất trên cả giỏ, tie-break id task). Khóa giá P_t vào DB; lần khóa kế tiếp (ADDITIONAL) chỉ định giá
 * task chưa có quỹ từ phần dự phòng còn lại, không bao giờ thêm quỹ ngoài 100%.
 */
@Service
public class PointPolicyService {

	public static final String NO_POLICY_LABEL = "Chưa có phương án điểm đã duyệt";

	record PolicyRow(UUID id, int versionNo, String status, int easy, int medium, int hard, int memberCount,
			UUID approvedBy, Instant approvedAt, Instant createdAt) {
	}

	private record PlannedTask(UUID taskId, String title, String difficulty, int weight, long units, UUID planId,
			UUID assigneeId) {
	}

	private record PlannedMember(UUID userId, UUID memberBudgetId, long initial, long reserveAfter, int weightSumAll,
			List<AllocationTask> existing, List<PlannedTask> fresh, List<String> warnings) {
	}

	private record MemberBudgetRow(UUID id, UUID memberId, long initial, long reserve, int weightSum) {
	}

	private record Plan(String basis, int versionNo, PolicyRow active, int memberCount, List<PlannedMember> members,
			List<PolicyIssue> issues) {
	}

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public PointPolicyService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, AuditWriter audit,
			NotificationService notifications, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------------ đọc

	@Transactional(readOnly = true)
	public PointPolicyResponse get(UUID projectId, CurrentUser user) {
		GroupMembership m = access.requireMember(projectId, user);
		Optional<PolicyRow> active = activePolicy(projectId);
		if (active.isEmpty()) {
			return new PointPolicyResponse(null, false, NO_POLICY_LABEL, null);
		}
		PolicyRow p = active.get();
		return new PointPolicyResponse(policyDto(p), true, null, m.isLeader() ? loadAllocation(projectId, p) : null);
	}

	@Transactional(readOnly = true)
	public PointPolicyPreviewResponse preview(UUID projectId, CurrentUser user, PointPolicyPreviewRequest req) {
		access.requireLeader(projectId, user);
		Plan plan = plan(projectId, req == null ? new PointPolicyPreviewRequest(null, null) : req);
		Allocation allocation = plannedAllocation(plan);
		long actual = allocation.members().stream().mapToLong(a -> a.reservePoints().pointUnits() + a.pricedPoints().pointUnits())
				.sum();
		SumCheck sum = new SumCheck(PointMath.TOTAL_UNITS, actual, actual == PointMath.TOTAL_UNITS);
		return new PointPolicyPreviewResponse(plan.basis(), plan.versionNo(), plan.memberCount(), allocation, sum,
				plan.issues(), plan.issues().isEmpty() && sum.ok());
	}

	// ------------------------------------------------------------------------------------------------ khóa

	@Transactional
	public ActivatePointPolicyResponse activate(UUID projectId, CurrentUser user, String idempotencyKey,
			ActivatePointPolicyRequest req) {
		GroupMembership lead = access.requireLeader(projectId, user);
		IdempotencyKey.require(idempotencyKey);
		if (req == null || !Boolean.TRUE.equals(req.confirmedWithMembers())) {
			throw ApiException.validation("confirmedWithMembers", "TL phải xác nhận phương án đã thống nhất với các thành viên.");
		}
		// tuần tự hóa mọi lần khóa/định giá trong nhóm
		jdbc.sql("SELECT id FROM projects WHERE id = :p FOR UPDATE").param("p", projectId).query(UUID.class).single();
		Plan plan = plan(projectId, req.asPreview());
		if (!plan.issues().isEmpty()) {
			throw issuesException(plan.issues());
		}
		Instant now = clock.instant();
		UUID policyId;
		if (plan.active() == null) {
			policyId = jdbc.sql("""
					INSERT INTO point_policies (project_id, version_no, member_count_at_baseline, created_by, created_at)
					VALUES (:p, :v, :n, :c, :at) RETURNING id
					""").param("p", projectId).param("v", plan.versionNo()).param("n", plan.memberCount())
					.param("c", lead.userId()).param("at", DbTime.toDb(now)).query(UUID.class).single();
		}
		else {
			policyId = plan.active().id();
		}
		int funded = 0;
		UUID firstNewBudget = null;
		for (PlannedMember pm : plan.members()) {
			UUID mbId = pm.memberBudgetId();
			if (mbId == null) {
				mbId = jdbc.sql("""
						INSERT INTO member_point_budgets (policy_id, project_id, member_id, initial_units, reserve_units,
						    baseline_weight_sum, created_at)
						VALUES (:pol, :p, :m, :i, :r, :w, :at) RETURNING id
						""").param("pol", policyId).param("p", projectId).param("m", pm.userId()).param("i", pm.initial())
						.param("r", pm.reserveAfter()).param("w", pm.weightSumAll()).param("at", DbTime.toDb(now))
						.query(UUID.class).single();
			}
			else if (!pm.fresh().isEmpty()) {
				jdbc.sql("UPDATE member_point_budgets SET reserve_units = :r WHERE id = :id")
						.param("r", pm.reserveAfter()).param("id", mbId).update();
			}
			for (PlannedTask t : pm.fresh()) {
				UUID budgetId = jdbc.sql("""
						INSERT INTO task_point_budgets (project_id, origin_member_budget_id, task_id, difficulty_at_lock,
						    weight_at_lock, point_units, locked_plan_id, locked_at)
						VALUES (:p, :mb, :t, :d, :w, :u, :plan, :at) RETURNING id
						""").param("p", projectId).param("mb", mbId).param("t", t.taskId()).param("d", t.difficulty())
						.param("w", t.weight()).param("u", t.units()).param("plan", t.planId()).param("at", DbTime.toDb(now))
						.query(UUID.class).single();
				jdbc.sql("""
						INSERT INTO task_point_assignments (project_id, budget_id, recipient_id, confirmed_by, agreed_at)
						VALUES (:p, :b, :r, :c, :at)
						""").param("p", projectId).param("b", budgetId).param("r", t.assigneeId()).param("c", lead.userId())
						.param("at", DbTime.toDb(now)).update();
				funded++;
				if (firstNewBudget == null) {
					firstNewBudget = budgetId;
				}
			}
		}
		if (plan.active() == null) {
			jdbc.sql("UPDATE point_policies SET status = 'ACTIVE', approved_by = :a, approved_at = :at WHERE id = :id")
					.param("a", lead.userId()).param("at", DbTime.toDb(now)).param("id", policyId).update();
		}
		PolicyRow row = activePolicy(projectId).orElseThrow();
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("basis", plan.basis());
		after.put("versionNo", row.versionNo());
		after.put("fundedTaskCount", funded);
		audit.log(projectId, lead.userId(), "POINT_POLICY", policyId, "POINT_POLICY_ACTIVATED", null, after);
		if (plan.active() == null || funded > 0) {
			String dedup = plan.active() == null ? "policy:" + policyId + ":activated"
					: "policy:" + policyId + ":priced:" + firstNewBudget;
			notifications.publish(new NotificationEvent(projectId, "POINT_POLICY_ACTIVATED", "POINT_POLICY", policyId, null,
					dedup, access.activeMemberIds(projectId), "Phương án điểm đã được khóa",
					plan.active() == null ? "TL đã khóa phương án điểm đóng góp của nhóm."
							: "TL đã định giá thêm " + funded + " task từ phần quỹ dự phòng.",
					Map.of("policyId", policyId.toString())));
		}
		return new ActivatePointPolicyResponse(policyDto(row), loadAllocation(projectId, row), funded);
	}

	private ApiException issuesException(List<PolicyIssue> issues) {
		boolean notApproved = issues.stream().anyMatch(i -> "POLICY_TASKS_NOT_APPROVED".equals(i.code()));
		String code = notApproved ? "POLICY_TASKS_NOT_APPROVED" : "POINT_POLICY_INVALID";
		List<ApiError.FieldError> fields = new ArrayList<>();
		for (PolicyIssue i : issues) {
			fields.add(new ApiError.FieldError(i.taskId() != null ? "tasks" : "participantUserIds", i.message()));
		}
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, issues.get(0).message(), fields);
	}

	// ------------------------------------------------------------------------------------------------ tính phương án

	private Plan plan(UUID projectId, PointPolicyPreviewRequest req) {
		Optional<PolicyRow> active = activePolicy(projectId);
		return active.isPresent() ? planAdditional(projectId, active.get(), req) : planInitial(projectId, req);
	}

	private Plan planInitial(UUID projectId, PointPolicyPreviewRequest req) {
		List<PolicyIssue> issues = new ArrayList<>();
		List<UUID> activeIds = lookups.activeMembers(projectId);
		List<UUID> requested = req.participantUserIds() == null || req.participantUserIds().isEmpty() ? activeIds
				: req.participantUserIds().stream().distinct().toList();
		List<UUID> valid = new ArrayList<>();
		for (UUID u : requested) {
			if (activeIds.contains(u)) {
				valid.add(u);
			}
			else {
				issues.add(new PolicyIssue("POINT_POLICY_INVALID", "Người này không phải thành viên đang hoạt động của nhóm.",
						null, u));
			}
		}
		// giữ thứ tự tham gia nhóm để hiển thị; id sắp ổn định để tie-break phần dư
		List<UUID> displayOrder = activeIds.stream().filter(valid::contains).toList();
		if (displayOrder.isEmpty()) {
			issues.add(new PolicyIssue("POINT_POLICY_INVALID", "Phương án cần ít nhất một người tham gia.", null, null));
			return new Plan("INITIAL", nextVersionNo(projectId), null, 0, List.of(), issues);
		}
		List<UUID> sortedIds = displayOrder.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
		long[] ones = new long[sortedIds.size()];
		java.util.Arrays.fill(ones, 1);
		long[] shares = PointMath.allocate(PointMath.TOTAL_UNITS, ones, sortedIds);
		Map<UUID, Long> baseline = new HashMap<>();
		for (int i = 0; i < sortedIds.size(); i++) {
			baseline.put(sortedIds.get(i), shares[i]);
		}
		Map<UUID, Long> reserves = reserveMap(req.reserves(), displayOrder, baseline, issues);
		Map<UUID, List<TaskFacts>> byMember = new HashMap<>();
		for (TaskFacts f : tasks.allFacts(projectId)) {
			if (!f.hasEffectivePlan() || f.difficulty() == null) {
				issues.add(new PolicyIssue("POLICY_TASKS_NOT_APPROVED",
						"Task \"" + f.title() + "\" chưa có kế hoạch được duyệt hoặc thiếu độ khó.", f.taskId(), f.assigneeId()));
				continue;
			}
			if (!valid.contains(f.assigneeId())) {
				issues.add(new PolicyIssue("POINT_POLICY_INVALID",
						"Task \"" + f.title() + "\" thuộc người không nằm trong phương án điểm.", f.taskId(), f.assigneeId()));
				continue;
			}
			byMember.computeIfAbsent(f.assigneeId(), k -> new ArrayList<>()).add(f);
		}
		PolicyRow defaults = new PolicyRow(null, nextVersionNo(projectId), "DRAFT", 1, 2, 3, displayOrder.size(), null, null,
				null);
		List<PlannedMember> members = new ArrayList<>();
		for (UUID uid : displayOrder) {
			long b = baseline.get(uid);
			long r = reserves.getOrDefault(uid, 0L);
			List<TaskFacts> mine = byMember.getOrDefault(uid, List.of());
			members.add(priceInitial(uid, b, r, mine, defaults, issues));
		}
		return new Plan("INITIAL", defaults.versionNo(), null, displayOrder.size(), members, issues);
	}

	private Plan planAdditional(UUID projectId, PolicyRow active, PointPolicyPreviewRequest req) {
		List<PolicyIssue> issues = new ArrayList<>();
		List<MemberBudgetRow> budgets = jdbc.sql("""
				SELECT mb.id, mb.member_id, mb.initial_units, mb.reserve_units, mb.baseline_weight_sum
				  FROM member_point_budgets mb JOIN project_members pm
				    ON pm.project_id = mb.project_id AND pm.user_id = mb.member_id
				 WHERE mb.policy_id = :pol ORDER BY pm.joined_at, mb.member_id
				""").param("pol", active.id())
				.query((rs, i) -> new MemberBudgetRow(rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
						rs.getLong("initial_units"), rs.getLong("reserve_units"), rs.getInt("baseline_weight_sum")))
				.list();
		List<UUID> memberIds = budgets.stream().map(MemberBudgetRow::memberId).toList();
		Map<UUID, Long> reserveNow = new HashMap<>();
		budgets.forEach(b -> reserveNow.put(b.memberId(), b.reserve()));
		// ADDITIONAL: R_i của request = phần muốn GIỮ LẠI sau khi định giá (mặc định 0)
		Map<UUID, Long> keep = reserveMap(req.reserves(), memberIds, reserveNow, issues);
		Set<UUID> priced = new HashSet<>(jdbc.sql("SELECT task_id FROM task_point_budgets WHERE project_id = :p")
				.param("p", projectId).query(UUID.class).list());
		Map<UUID, List<TaskFacts>> fresh = new HashMap<>();
		for (TaskFacts f : tasks.allFacts(projectId)) {
			if (priced.contains(f.taskId()) || !f.hasEffectivePlan() || f.difficulty() == null
					|| "ACCEPTED".equals(f.roundState())) {
				continue;
			}
			if (!memberIds.contains(f.assigneeId())) {
				issues.add(new PolicyIssue("POINT_POLICY_INVALID",
						"Task \"" + f.title() + "\" thuộc người chưa có phần quỹ trong phương án điểm hiện hành.", f.taskId(),
						f.assigneeId()));
				continue;
			}
			fresh.computeIfAbsent(f.assigneeId(), k -> new ArrayList<>()).add(f);
		}
		List<PlannedMember> members = new ArrayList<>();
		for (MemberBudgetRow mb : budgets) {
			List<AllocationTask> existing = existingTasks(mb.id());
			members.add(priceAdditional(mb, keep.getOrDefault(mb.memberId(), 0L), existing,
					fresh.getOrDefault(mb.memberId(), List.of()), active, issues));
		}
		return new Plan("ADDITIONAL", active.versionNo(), active, active.memberCount(), members, issues);
	}

	private PlannedMember priceInitial(UUID uid, long b, long r, List<TaskFacts> mine, PolicyRow w, List<PolicyIssue> issues) {
		List<String> warnings = new ArrayList<>();
		if (mine.isEmpty()) {
			warnings.add("W_i = 0: giữ B_i làm quỹ chưa phân bổ");
			return new PlannedMember(uid, null, b, b, 0, List.of(), List.of(), warnings);
		}
		long pool = b - r;
		if (pool <= 0) {
			issues.add(new PolicyIssue("POINT_POLICY_INVALID",
					"Phần dự phòng của " + lookups.userName(uid) + " chiếm toàn bộ quỹ, không còn quỹ để định giá task.", null, uid));
			return new PlannedMember(uid, null, b, b, 0, List.of(), List.of(), warnings);
		}
		List<PlannedTask> fresh = allocateTasks(uid, pool, mine, w, issues);
		int weightSum = fresh.stream().mapToInt(PlannedTask::weight).sum();
		return new PlannedMember(uid, null, b, r, weightSum, List.of(), fresh, warnings);
	}

	private PlannedMember priceAdditional(MemberBudgetRow mb, long keep, List<AllocationTask> existing, List<TaskFacts> mine,
			PolicyRow w, List<PolicyIssue> issues) {
		if (mine.isEmpty()) {
			return new PlannedMember(mb.memberId(), mb.id(), mb.initial(), mb.reserve(), mb.weightSum(), existing, List.of(),
					List.of());
		}
		long pool = mb.reserve() - keep;
		if (pool <= 0) {
			issues.add(new PolicyIssue("POINT_POLICY_INVALID", "Quỹ dự phòng của " + lookups.userName(mb.memberId())
					+ " đã hết: cần lập phương án phân bổ lại phần quỹ còn tự do.", null, mb.memberId()));
			return new PlannedMember(mb.memberId(), mb.id(), mb.initial(), mb.reserve(), mb.weightSum(), existing, List.of(),
					List.of());
		}
		List<PlannedTask> fresh = allocateTasks(mb.memberId(), pool, mine, w, issues);
		long spent = fresh.stream().mapToLong(PlannedTask::units).sum();
		return new PlannedMember(mb.memberId(), mb.id(), mb.initial(), mb.reserve() - spent, mb.weightSum(), existing, fresh,
				List.of());
	}

	/** P_t = pool × w / W bằng phần dư lớn nhất trên cả giỏ (tie-break id task). */
	private List<PlannedTask> allocateTasks(UUID uid, long pool, List<TaskFacts> mine, PolicyRow w, List<PolicyIssue> issues) {
		long[] weights = new long[mine.size()];
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < mine.size(); i++) {
			weights[i] = PointMath.weightOf(mine.get(i).difficulty(), w.easy(), w.medium(), w.hard());
			ids.add(mine.get(i).taskId());
		}
		long[] units = PointMath.allocate(pool, weights, ids);
		List<PlannedTask> out = new ArrayList<>();
		for (int i = 0; i < mine.size(); i++) {
			TaskFacts f = mine.get(i);
			if (units[i] <= 0) {
				issues.add(new PolicyIssue("POINT_POLICY_INVALID",
						"Quỹ của " + lookups.userName(uid) + " quá nhỏ để định giá task \"" + f.title() + "\".", f.taskId(), uid));
			}
			out.add(new PlannedTask(f.taskId(), f.title(), f.difficulty(), (int) weights[i], units[i], f.effectivePlanId(),
					f.assigneeId()));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private Map<UUID, Long> reserveMap(List<ReserveInput> reserves, List<UUID> members, Map<UUID, Long> limit,
			List<PolicyIssue> issues) {
		Map<UUID, Long> map = new HashMap<>();
		if (reserves == null) {
			return map;
		}
		for (ReserveInput r : reserves) {
			if (r == null || r.userId() == null) {
				continue;
			}
			long units = r.reservePointUnits() == null ? 0 : r.reservePointUnits();
			if (!members.contains(r.userId())) {
				issues.add(new PolicyIssue("POINT_POLICY_INVALID", "Người có phần dự phòng không nằm trong phương án.", null,
						r.userId()));
			}
			else if (units < 0 || units > limit.getOrDefault(r.userId(), 0L)) {
				issues.add(new PolicyIssue("POINT_POLICY_INVALID",
						"Phần dự phòng vượt phần quỹ còn tự do của " + lookups.userName(r.userId()) + ".", null, r.userId()));
			}
			else {
				map.put(r.userId(), units);
			}
		}
		return map;
	}

	private int nextVersionNo(UUID projectId) {
		return jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM point_policies WHERE project_id = :p")
				.param("p", projectId).query(Integer.class).single();
	}

	Optional<PolicyRow> activePolicy(UUID projectId) {
		return jdbc.sql("""
				SELECT id, version_no, status, easy_weight, medium_weight, hard_weight, member_count_at_baseline,
				       approved_by, approved_at, created_at
				  FROM point_policies WHERE project_id = :p AND status = 'ACTIVE'
				""").param("p", projectId)
				.query((rs, i) -> new PolicyRow(rs.getObject("id", UUID.class), rs.getInt("version_no"), rs.getString("status"),
						rs.getInt("easy_weight"), rs.getInt("medium_weight"), rs.getInt("hard_weight"),
						rs.getInt("member_count_at_baseline"), rs.getObject("approved_by", UUID.class),
						DbTime.instant(rs, "approved_at"), DbTime.instant(rs, "created_at")))
				.optional();
	}

	private PointPolicyDto policyDto(PolicyRow p) {
		Map<String, Integer> weights = new LinkedHashMap<>();
		weights.put("EASY", p.easy());
		weights.put("MEDIUM", p.medium());
		weights.put("HARD", p.hard());
		UserRef approver = p.approvedBy() == null ? null : lookups.user(p.approvedBy());
		return new PointPolicyDto(p.id(), p.versionNo(), p.status(), PointAmount.of(PointMath.TOTAL_UNITS), weights,
				p.memberCount(), approver, p.approvedAt(), p.createdAt());
	}

	private List<AllocationTask> existingTasks(UUID memberBudgetId) {
		return jdbc.sql("""
				SELECT b.task_id, b.difficulty_at_lock, b.weight_at_lock, b.point_units
				  FROM task_point_budgets b WHERE b.origin_member_budget_id = :mb AND b.state = 'FUNDED'
				 ORDER BY b.locked_at, b.id
				""").param("mb", memberBudgetId)
				.query((rs, i) -> new AllocationTask(rs.getObject("task_id", UUID.class),
						lookups.taskTitle(rs.getObject("task_id", UUID.class)), rs.getString("difficulty_at_lock"),
						rs.getInt("weight_at_lock"), PointAmount.of(rs.getLong("point_units"))))
				.list();
	}

	/** Bảng W_i / B_i / P_t đã khóa (đọc từ DB). */
	Allocation loadAllocation(UUID projectId, PolicyRow policy) {
		record Row(UUID id, UUID memberId, long initial, long reserve, int weightSum) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT mb.id, mb.member_id, mb.initial_units, mb.reserve_units, mb.baseline_weight_sum
				  FROM member_point_budgets mb JOIN project_members pm
				    ON pm.project_id = mb.project_id AND pm.user_id = mb.member_id
				 WHERE mb.policy_id = :pol ORDER BY pm.joined_at, mb.member_id
				""").param("pol", policy.id())
				.query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("member_id", UUID.class),
						rs.getLong("initial_units"), rs.getLong("reserve_units"), rs.getInt("baseline_weight_sum")))
				.list();
		List<AllocationMember> members = new ArrayList<>();
		long unallocated = 0;
		for (Row r : rows) {
			List<AllocationTask> ts = existingTasks(r.id());
			long priced = ts.stream().mapToLong(t -> t.points().pointUnits()).sum();
			List<String> warnings = ts.isEmpty() ? List.of("W_i = 0: giữ B_i làm quỹ chưa phân bổ") : List.of();
			members.add(new AllocationMember(lookups.user(r.memberId()), PointAmount.of(r.initial()),
					PointAmount.of(r.reserve()), PointAmount.of(priced), ts.stream().mapToInt(AllocationTask::weight).sum(),
					ts.size(), ts, warnings));
			unallocated += r.reserve();
		}
		return new Allocation(PointAmount.of(PointMath.TOTAL_UNITS), members, PointAmount.of(unallocated));
	}

	private Allocation plannedAllocation(Plan plan) {
		List<AllocationMember> out = new ArrayList<>();
		long unallocated = 0;
		for (PlannedMember pm : plan.members()) {
			List<AllocationTask> ts = new ArrayList<>(pm.existing());
			for (PlannedTask t : pm.fresh()) {
				ts.add(new AllocationTask(t.taskId(), t.title(), t.difficulty(), t.weight(), PointAmount.of(t.units())));
			}
			long priced = ts.stream().mapToLong(t -> t.points().pointUnits()).sum();
			out.add(new AllocationMember(lookups.user(pm.userId()), PointAmount.of(pm.initial()), PointAmount.of(pm.reserveAfter()),
					PointAmount.of(priced), ts.stream().mapToInt(AllocationTask::weight).sum(), ts.size(), ts, pm.warnings()));
			unallocated += pm.reserveAfter();
		}
		return new Allocation(PointAmount.of(PointMath.TOTAL_UNITS), out, PointAmount.of(unallocated));
	}
}
