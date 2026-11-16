package app.hanare.testsupport;

import app.hanare.common.DbTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Tạo dữ liệu mẫu bằng SQL trực tiếp (bỏ qua service) cho test BE-A/BE-B: user, nhóm (TL + thành viên), lịch,
 * kỳ checkpoint, task đã duyệt kế hoạch, lần nộp/quyết định, và kịch bản quỹ điểm (nhóm 5 người, A có task 1/2/3).
 * Mọi thời gian dùng {@link Instant}; ghi DB qua {@link DbTime#toDb}.
 */
public class DbFixtures {

	public static final String PASSWORD = "Passw0rd!";
	/** Mốc mẫu: chốt 22:00 thứ Sáu 13/11/2026 giờ VN (UTC+7) = 15:00Z; kỳ 1 = (13/11 15:00Z, 20/11 15:00Z]. */
	public static final Instant PROJECT_START = Instant.parse("2026-11-13T15:00:00Z");
	public static final Duration WEEK = Duration.ofDays(7);

	private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
	private static final AtomicInteger SEQ = new AtomicInteger();

	private final JdbcClient jdbc;

	public DbFixtures(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------ refs
	public record UserRef(UUID id, String name, String email) {
	}

	/** members không gồm leader; {@link #all()} = leader trước, rồi members. */
	public record GroupRef(UUID projectId, UserRef leader, List<UserRef> members) {
		public List<UserRef> all() {
			List<UserRef> all = new ArrayList<>();
			all.add(leader);
			all.addAll(members);
			return all;
		}
	}

	public record CycleRef(UUID id, int sequenceNo, Instant periodStart, Instant closesAt) {
	}

	public record TaskRef(UUID taskId, UUID roundId, UUID planId, UUID assigneeId, UUID cycleId, Instant deadline) {
	}

	// ------------------------------------------------------------------ reset
	/** Xóa toàn bộ dữ liệu (TRUNCATE, giữ schema + flyway_schema_history). Dùng trong @BeforeEach nếu cần cô lập. */
	public void resetData() {
		List<String> tables = jdbc.sql("""
				SELECT quote_ident(tablename) FROM pg_tables
				 WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
				""").query(String.class).list();
		if (!tables.isEmpty()) {
			jdbc.sql("TRUNCATE " + String.join(", ", tables) + " CASCADE").update();
		}
	}

	// ------------------------------------------------------------------ users / groups
	public UserRef user(String name) {
		return user(name, name.toLowerCase().replaceAll("[^a-z0-9]+", ".") + "." + SEQ.incrementAndGet() + "@example.test");
	}

	public UserRef user(String name, String email) {
		UUID id = jdbc.sql("INSERT INTO users (name, email, password_hash) VALUES (:n, :e, :p) RETURNING id")
				.param("n", name).param("e", email).param("p", PASSWORD_HASH).query(UUID.class).single();
		return new UserRef(id, name, email);
	}

	public UUID project(UserRef creator, String name) {
		return jdbc.sql("""
				INSERT INTO projects (name, start_at, deadline, created_by)
				VALUES (:n, :s, :d, :c) RETURNING id
				""")
				.param("n", name).param("s", DbTime.toDb(PROJECT_START))
				.param("d", DbTime.toDb(PROJECT_START.plus(Duration.ofDays(70)))).param("c", creator.id())
				.query(UUID.class).single();
	}

	public void addMember(UUID projectId, UserRef user, String role) {
		jdbc.sql("INSERT INTO project_members (project_id, user_id, role, joined_at) VALUES (:p, :u, :r, :j)")
				.param("p", projectId).param("u", user.id()).param("r", role)
				.param("j", DbTime.toDb(PROJECT_START.minus(Duration.ofDays(1)))).update();
	}

	/** Nhóm gồm 1 TL + (size - 1) thành viên, tất cả đang hoạt động. */
	public GroupRef group(String name, int size) {
		UserRef leader = user("Leader " + name);
		UUID projectId = project(leader, name);
		addMember(projectId, leader, "LEAD");
		List<UserRef> members = new ArrayList<>();
		for (int i = 1; i < size; i++) {
			UserRef m = user("Member" + i + " " + name);
			addMember(projectId, m, "MEMBER");
			members.add(m);
		}
		return new GroupRef(projectId, leader, members);
	}

	/** Nhóm 5 người (ví dụ spec 0.9 / 12.10). */
	public GroupRef groupOf5() {
		return group("Nhóm mẫu " + SEQ.incrementAndGet(), 5);
	}

	// ------------------------------------------------------------------ schedule / cycles
	/** Lịch v1: chốt 22:00 thứ Sáu, giờ VN, nhắc trước 420 phút. */
	public UUID schedule(UUID projectId, UUID recordedBy) {
		return jdbc.sql("""
				INSERT INTO checkpoint_schedules (project_id, version_no, weekday, cutoff_local_time, timezone,
				    effective_from, agreed_at, recorded_by)
				VALUES (:p, 1, 5, '22:00', 'Asia/Ho_Chi_Minh', :f, :f, :r) RETURNING id
				""")
				.param("p", projectId).param("f", DbTime.toDb(PROJECT_START)).param("r", recordedBy)
				.query(UUID.class).single();
	}

	public CycleRef cycle(UUID projectId, UUID scheduleId, int sequenceNo, UUID previousCycleId, Instant periodStart,
			Instant closesAt, String status) {
		UUID id = jdbc.sql("""
				INSERT INTO checkpoint_cycles (project_id, schedule_id, sequence_no, previous_cycle_id, period_start_at,
				    opens_at, reminder_at, closes_at, status)
				VALUES (:p, :s, :n, :prev, :start, :start, :rem, :close, :st) RETURNING id
				""")
				.param("p", projectId).param("s", scheduleId).param("n", sequenceNo).param("prev", previousCycleId)
				.param("start", DbTime.toDb(periodStart)).param("rem", DbTime.toDb(closesAt.minus(Duration.ofHours(7))))
				.param("close", DbTime.toDb(closesAt)).param("st", status).query(UUID.class).single();
		return new CycleRef(id, sequenceNo, periodStart, closesAt);
	}

	/** n kỳ liên tiếp 7 ngày bắt đầu từ {@link #PROJECT_START}, trạng thái SCHEDULED. */
	public List<CycleRef> weeklyCycles(UUID projectId, UUID scheduleId, int n) {
		List<CycleRef> cycles = new ArrayList<>();
		CycleRef prev = null;
		for (int i = 1; i <= n; i++) {
			Instant start = PROJECT_START.plus(WEEK.multipliedBy(i - 1L));
			prev = cycle(projectId, scheduleId, i, prev == null ? null : prev.id(), start, start.plus(WEEK), "SCHEDULED");
			cycles.add(prev);
		}
		return cycles;
	}

	// ------------------------------------------------------------------ tasks
	/**
	 * Task + vòng 1 ACTIVE + revision APPROVED (MEMBER_PLAN) giao cho assignee, deadline thuộc kỳ.
	 * difficulty: EASY | MEDIUM | HARD.
	 */
	public TaskRef approvedTask(UUID projectId, UUID leaderId, UUID assigneeId, CycleRef cycle, String difficulty,
			Instant deadline) {
		UUID taskId = jdbc.sql("INSERT INTO tasks (project_id, created_by) VALUES (:p, :c) RETURNING id")
				.param("p", projectId).param("c", leaderId).query(UUID.class).single();
		UUID roundId = jdbc.sql("""
				INSERT INTO task_review_rounds (project_id, task_id, round_no, cycle_id, reason, state, created_by)
				VALUES (:p, :t, 1, :c, 'INITIAL', 'DRAFT', :u) RETURNING id
				""")
				.param("p", projectId).param("t", taskId).param("c", cycle.id()).param("u", leaderId)
				.query(UUID.class).single();
		UUID planId = jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, proposed_cycle_id, assignee_id, title,
				    description, expected_output, start_at, deadline, difficulty, status, created_by, submitted_by,
				    submitted_at, reviewed_by, reviewed_at, approval_source)
				VALUES (:p, :r, 1, :c, :a, :title, 'Phạm vi mẫu', 'Đầu ra mẫu', :start, :dl, :d, 'APPROVED', :lead, :a,
				    :start, :lead, :start, 'MEMBER_PLAN') RETURNING id
				""")
				.param("p", projectId).param("r", roundId).param("c", cycle.id()).param("a", assigneeId)
				.param("title", "Task " + difficulty + " " + SEQ.incrementAndGet())
				.param("start", DbTime.toDb(cycle.periodStart())).param("dl", DbTime.toDb(deadline))
				.param("d", difficulty).param("lead", leaderId).query(UUID.class).single();
		jdbc.sql("""
				UPDATE task_review_rounds SET state = 'ACTIVE', effective_plan_id = :plan, started_at = :start WHERE id = :r
				""")
				.param("plan", planId).param("start", DbTime.toDb(cycle.periodStart())).param("r", roundId).update();
		return new TaskRef(taskId, roundId, planId, assigneeId, cycle.id(), deadline);
	}

	public UUID submission(UUID projectId, TaskRef task, int submissionNo, Instant at) {
		return jdbc.sql("""
				INSERT INTO task_submissions (project_id, round_id, plan_revision_id, submission_no, submitted_by,
				    result_note, evidence, submitted_at)
				VALUES (:p, :r, :plan, :n, :by, 'Đã làm xong',
				    CAST('[{"type":"URL","url":"https://example.test/evidence","label":"Minh chứng"}]' AS jsonb), :at)
				RETURNING id
				""")
				.param("p", projectId).param("r", task.roundId()).param("plan", task.planId()).param("n", submissionNo)
				.param("by", task.assigneeId()).param("at", DbTime.toDb(at)).query(UUID.class).single();
	}

	/** decision: ACCEPTED | FIX_REQUIRED | UPDATE_REQUIRED (hai loại sau tự điền issue_description). */
	public UUID review(UUID projectId, UUID submissionId, UUID reviewerId, String decision, Instant at) {
		return jdbc.sql("""
				INSERT INTO task_result_reviews (project_id, submission_id, decision, reviewer_id, issue_description, reviewed_at)
				VALUES (:p, :s, :d, :r, :issue, :at) RETURNING id
				""")
				.param("p", projectId).param("s", submissionId).param("d", decision).param("r", reviewerId)
				.param("issue", "ACCEPTED".equals(decision) ? null : "Cần chỉnh lại theo yêu cầu").param("at", DbTime.toDb(at))
				.query(UUID.class).single();
	}

	// ------------------------------------------------------------------ points
	public UUID pointPolicyDraft(UUID projectId, UUID createdBy, int memberCount) {
		return jdbc.sql("""
				INSERT INTO point_policies (project_id, version_no, member_count_at_baseline, created_by)
				VALUES (:p, (SELECT COALESCE(MAX(version_no), 0) + 1 FROM point_policies WHERE project_id = :p), :n, :c)
				RETURNING id
				""")
				.param("p", projectId).param("n", memberCount).param("c", createdBy).query(UUID.class).single();
	}

	public UUID memberBudget(UUID policyId, UUID projectId, UUID memberId, long initialUnits, long reserveUnits,
			int weightSum) {
		return jdbc.sql("""
				INSERT INTO member_point_budgets (policy_id, project_id, member_id, initial_units, reserve_units, baseline_weight_sum)
				VALUES (:pol, :p, :m, :i, :r, :w) RETURNING id
				""")
				.param("pol", policyId).param("p", projectId).param("m", memberId).param("i", initialUnits)
				.param("r", reserveUnits).param("w", weightSum).query(UUID.class).single();
	}

	public UUID taskBudget(UUID projectId, UUID memberBudgetId, TaskRef task, String difficulty, int weight, long units) {
		return jdbc.sql("""
				INSERT INTO task_point_budgets (project_id, origin_member_budget_id, task_id, difficulty_at_lock,
				    weight_at_lock, point_units, locked_plan_id)
				VALUES (:p, :mb, :t, :d, :w, :u, :plan) RETURNING id
				""")
				.param("p", projectId).param("mb", memberBudgetId).param("t", task.taskId()).param("d", difficulty)
				.param("w", weight).param("u", units).param("plan", task.planId()).query(UUID.class).single();
	}

	public void activatePolicy(UUID policyId, UUID approvedBy) {
		jdbc.sql("UPDATE point_policies SET status = 'ACTIVE', approved_by = :a, approved_at = now() WHERE id = :id")
				.param("a", approvedBy).param("id", policyId).update();
	}

	/** Người nhận ban đầu = người gốc (previous_assignment_id = NULL). */
	public UUID assignment(UUID projectId, UUID budgetId, UUID recipientId, UUID confirmedBy) {
		return jdbc.sql("""
				INSERT INTO task_point_assignments (project_id, budget_id, recipient_id, confirmed_by)
				VALUES (:p, :b, :r, :c) RETURNING id
				""")
				.param("p", projectId).param("b", budgetId).param("r", recipientId).param("c", confirmedBy)
				.query(UUID.class).single();
	}

	public UUID settlement(UUID projectId, UUID budgetId, UUID assignmentId, UUID recipientId, UUID submissionId,
			UUID approvedBy) {
		return jdbc.sql("""
				INSERT INTO task_point_settlements (project_id, budget_id, assignment_id, recipient_id,
				    accepted_submission_id, approved_by)
				VALUES (:p, :b, :a, :r, :s, :by) RETURNING id
				""")
				.param("p", projectId).param("b", budgetId).param("a", assignmentId).param("r", recipientId)
				.param("s", submissionId).param("by", approvedBy).query(UUID.class).single();
	}

	public UUID ledgerAward(UUID projectId, UUID budgetId, UUID settlementId, UUID recipientId, long units, String key) {
		return jdbc.sql("""
				INSERT INTO point_ledger_entries (project_id, budget_id, settlement_id, recipient_id, delta_units,
				    entry_type, idempotency_key)
				VALUES (:p, :b, :s, :r, :u, 'AWARD', :k) RETURNING id
				""")
				.param("p", projectId).param("b", budgetId).param("s", settlementId).param("r", recipientId)
				.param("u", units).param("k", key).query(UUID.class).single();
	}

	/**
	 * Kịch bản điểm của spec 0.9/12.10: nhóm 5 người, A (= members[0]) có 3 task Dễ/Trung bình/Khó nằm trong kỳ 1
	 * với P_t = 33.333 / 66.667 / 100.000 units (3,3333 / 6,6667 / 10,0000 điểm %); B..D và TL chưa có task nên giữ
	 * nguyên phần 200.000 units làm dự phòng. Policy đã ACTIVE; mỗi task có assignment CURRENT cho A.
	 * Chưa có lần nộp/duyệt/ghi điểm nào.
	 */
	public record PointsScenario(GroupRef group, UserRef memberA, CycleRef cycle, UUID policyId, List<TaskRef> tasks,
			List<UUID> budgets, List<UUID> assignments) {
	}

	public PointsScenario pointsScenarioFiveMembers() {
		GroupRef g = groupOf5();
		UUID schedule = schedule(g.projectId(), g.leader().id());
		CycleRef cycle = weeklyCycles(g.projectId(), schedule, 2).get(0);
		UserRef a = g.members().get(0);
		UUID policy = pointPolicyDraft(g.projectId(), g.leader().id(), 5);
		UUID budgetA = null;
		for (UserRef u : g.all()) {
			if (u.id().equals(a.id())) {
				budgetA = memberBudget(policy, g.projectId(), u.id(), 200_000, 0, 6);
			}
			else {
				memberBudget(policy, g.projectId(), u.id(), 200_000, 200_000, 0);
			}
		}
		String[] diffs = { "EASY", "MEDIUM", "HARD" };
		int[] weights = { 1, 2, 3 };
		long[] units = { 33_333, 66_667, 100_000 };
		List<TaskRef> tasks = new ArrayList<>();
		List<UUID> budgets = new ArrayList<>();
		List<UUID> assignments = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			// deadline lần lượt thứ Ba, thứ Tư, thứ Sáu(= đúng cutoff) của kỳ 1
			Instant deadline = i == 2 ? cycle.closesAt() : cycle.periodStart().plus(Duration.ofDays(4 + i));
			TaskRef t = approvedTask(g.projectId(), g.leader().id(), a.id(), cycle, diffs[i], deadline);
			UUID b = taskBudget(g.projectId(), budgetA, t, diffs[i], weights[i], units[i]);
			assignments.add(assignment(g.projectId(), b, a.id(), g.leader().id()));
			tasks.add(t);
			budgets.add(b);
		}
		activatePolicy(policy, g.leader().id());
		return new PointsScenario(g, a, cycle, policy, tasks, budgets, assignments);
	}

	/**
	 * Nộp lúc {@code submittedAt} rồi TL chấp nhận lúc {@code reviewedAt} cho task thứ i của kịch bản; trả submission id.
	 */
	public UUID submitAndAccept(PointsScenario s, int taskIndex, Instant submittedAt, Instant reviewedAt) {
		TaskRef t = s.tasks().get(taskIndex);
		UUID sub = submission(s.group().projectId(), t, 1, submittedAt);
		review(s.group().projectId(), sub, s.group().leader().id(), "ACCEPTED", reviewedAt);
		return sub;
	}
}
