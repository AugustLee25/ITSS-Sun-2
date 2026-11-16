package app.hanare.task;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Plan;
import app.hanare.task.TaskViews.Viewer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mục F: tạo/giao task, kế hoạch (DRAFT → PENDING_APPROVAL → APPROVED/CHANGES_REQUESTED), revision, tiến độ, người duyệt độc lập. */
@Service
public class TaskService {

	public record CreateTaskRequest(String title, UUID assigneeId, UUID cycleId, String description, String expectedOutput,
			Instant startAt, Instant deadline, String difficulty) {
	}

	public record UpdatePlanRequest(String title, String description, String expectedOutput, UUID cycleId, UUID assigneeId,
			Instant startAt, Instant deadline, String difficulty, Long version) {
	}

	public record ProposeChangeRequest(String changeReason, String title, String description, String expectedOutput,
			UUID cycleId, UUID assigneeId, Instant startAt, Instant deadline, String difficulty, Boolean submitNow) {
	}

	public record VersionedRequest(Long version) {
	}

	public record ReviewPlanRequest(String decision, String note, Long version) {
	}

	public record ProgressUpdateRequest(String note, String riskStatus, Instant expectedCompletionAt) {
	}

	public record SetIndependentReviewerRequest(UUID reviewerUserId) {
	}

	private static final List<String> DIFFICULTIES = List.of("EASY", "MEDIUM", "HARD");
	private static final List<String> RISKS = List.of("NORMAL", "UNKNOWN", "AT_RISK", "BLOCKED");

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Scope.Factory scopes;
	private final TaskReader reader;
	private final TaskViews views;
	private final Ops ops;
	private final CycleLifecycle lifecycle;

	public TaskService(JdbcClient jdbc, GroupAccess access, Scope.Factory scopes, TaskReader reader, TaskViews views, Ops ops,
			CycleLifecycle lifecycle) {
		this.jdbc = jdbc;
		this.access = access;
		this.scopes = scopes;
		this.reader = reader;
		this.views = views;
		this.ops = ops;
		this.lifecycle = lifecycle;
	}

	// ------------------------------------------------------------------ quy tắc dùng chung
	static void requireDifficulty(J.Check ck, String field, String d) {
		ck.require(d != null && DIFFICULTIES.contains(d), field, "Độ khó phải là EASY, MEDIUM hoặc HARD");
	}

	/** Giới hạn cuối ngày deadline nhóm (23:59:59.999 theo múi giờ nhóm). */
	Instant groupLimit(Scope s) {
		Instant dl = jdbc.sql("SELECT deadline FROM projects WHERE id = :p").param("p", s.projectId())
				.query((rs, i) -> J.inst(rs, "deadline")).single();
		LocalDate d = dl.atZone(s.tz()).toLocalDate();
		return d.plusDays(1).atStartOfDay(s.tz()).toInstant().minusMillis(1);
	}

	/** Kiểm tra giờ/kỳ/người phụ trách; ném mã lỗi đúng contract. */
	void checkPlanTimes(Scope s, CycleRow cycle, Instant startAt, Instant deadline, UUID assigneeId, boolean checkClosed) {
		if (assigneeId != null && !access.isActiveMember(s.projectId(), assigneeId)) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "ASSIGNEE_NOT_IN_GROUP", "Người phụ trách không thuộc nhóm.");
		}
		if (checkClosed && cycle.isClosed(s.now())) {
			throw ApiException.of(HttpStatus.CONFLICT, "CYCLE_CLOSED", "Kỳ checkpoint này đã đóng.");
		}
		if (startAt != null && deadline != null && startAt.isAfter(deadline)) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "START_AFTER_DEADLINE", "Giờ bắt đầu phải trước hoặc bằng deadline.");
		}
		if (deadline != null && !cycle.contains(deadline)) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DEADLINE_OUTSIDE_CYCLE",
					"Deadline phải nằm trong " + s.cycleLabel(cycle) + ".");
		}
		if (deadline != null && deadline.isAfter(groupLimit(s))) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "DEADLINE_AFTER_GROUP_DEADLINE", "Deadline vượt deadline của nhóm.");
		}
	}

	private Viewer viewer(GroupMembership m) {
		return new Viewer(m.userId(), m.role().name());
	}

	// ------------------------------------------------------------------ GET /groups/{g}/tasks
	@Transactional(readOnly = true)
	public Map<String, Object> list(UUID projectId, CurrentUser user, Map<String, String> q) {
		GroupMembership m = access.requireMember(projectId, user);
		Scope s = scopes.of(projectId);
		Viewer v = viewer(m);
		String tab = q.getOrDefault("tab", "ALL");
		List<Ctx> ctxs = new ArrayList<>(reader.loadAll(reader.currentRoundIds(projectId)).values());
		List<Map<String, Object>> all = new ArrayList<>(views.summaries(s, ctxs, v));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> t : all) {
			if (matches(t, tab, v, q)) {
				out.add(t);
			}
		}
		Map<UUID, Instant> created = new java.util.HashMap<>();
		for (Ctx c : ctxs) {
			created.put(c.round().taskId(), c.round().taskCreatedAt());
		}
		out.sort(Comparator.<Map<String, Object>, String>comparing(t -> (String) t.get("deadline"),
				Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(t -> created.get((UUID) t.get("id"))));
		int size = Math.min(Math.max(intOr(q.get("size"), 20), 1), 100);
		int page = Math.max(intOr(q.get("page"), 0), 0);
		int from = Math.min(page * size, out.size());
		List<Map<String, Object>> slice = out.subList(from, Math.min(from + size, out.size()));
		return J.o("items", slice, "page", page, "size", size, "totalItems", out.size(),
				"totalPages", (out.size() + size - 1) / size);
	}

	private static int intOr(String v, int d) {
		try {
			return v == null ? d : Integer.parseInt(v);
		}
		catch (NumberFormatException e) {
			throw ApiException.validation("page", "Giá trị không hợp lệ");
		}
	}

	@SuppressWarnings("unchecked")
	private boolean matches(Map<String, Object> t, String tab, Viewer v, Map<String, String> q) {
		String status = (String) t.get("taskStatus");
		String risk = (String) t.get("riskStatus");
		Map<String, Object> exec = (Map<String, Object>) t.get("currentExecutor");
		String pending = (String) t.get("pendingRevisionStatus");
		String plan = (String) t.get("planStatus");
		switch (tab) {
			case "MINE" -> {
				if (!v.id().equals(exec.get("userId"))) {
					return false;
				}
			}
			case "PENDING_APPROVAL" -> {
				if (!"PENDING_APPROVAL".equals(plan) && !"PENDING_APPROVAL".equals(pending)) {
					return false;
				}
			}
			case "NEEDS_EDIT" -> {
				boolean e1 = (plan != null && List.of("DRAFT", "CHANGES_REQUESTED").contains(plan))
						|| (pending != null && List.of("DRAFT", "CHANGES_REQUESTED").contains(pending));
				if (!e1) {
					return false;
				}
			}
			case "NEEDS_ATTENTION" -> {
				boolean att = Boolean.TRUE.equals(t.get("isLate")) || (risk != null && List.of("AT_RISK", "BLOCKED").contains(risk))
						|| Boolean.TRUE.equals(t.get("needsCarryover")) || List.of("FIX_REQUIRED", "UPDATE_REQUIRED").contains(status);
				if (!att) {
					return false;
				}
			}
			case "ALL" -> {
			}
			default -> throw ApiException.validation("tab", "Giá trị tab không hợp lệ");
		}
		if (q.get("cycleId") != null && !q.get("cycleId").equals(((Map<String, Object>) t.get("cycle")).get("id").toString())) {
			return false;
		}
		if (q.get("assigneeId") != null && !q.get("assigneeId").equals(exec.get("userId").toString())) {
			return false;
		}
		if (q.get("planStatus") != null && !q.get("planStatus").equals(plan) && !q.get("planStatus").equals(pending)) {
			return false;
		}
		if (q.get("taskStatus") != null && !q.get("taskStatus").equals(status)) {
			return false;
		}
		if (q.get("difficulty") != null && !q.get("difficulty").equals(t.get("difficulty"))) {
			return false;
		}
		String dl = (String) t.get("deadline");
		if (q.get("deadlineFrom") != null && (dl == null || Instant.parse(dl).isBefore(Instant.parse(q.get("deadlineFrom"))))) {
			return false;
		}
		if (q.get("deadlineTo") != null && (dl == null || Instant.parse(dl).isAfter(Instant.parse(q.get("deadlineTo"))))) {
			return false;
		}
		return q.get("q") == null || ((String) t.get("title")).toLowerCase().contains(q.get("q").toLowerCase());
	}

	// ------------------------------------------------------------------ POST /groups/{g}/tasks
	@Transactional
	public Map<String, Object> create(UUID projectId, CurrentUser user, CreateTaskRequest r) {
		access.requireLeader(projectId, user);
		J.Check ck = J.check().len(r.title(), "title", 3, 120).len(r.description(), "description", 1, 4000)
				.len(r.expectedOutput(), "expectedOutput", 1, 2000).require(r.assigneeId() != null, "assigneeId", "Bắt buộc")
				.require(r.cycleId() != null, "cycleId", "Bắt buộc").require(r.startAt() != null, "startAt", "Bắt buộc")
				.require(r.deadline() != null, "deadline", "Bắt buộc");
		requireDifficulty(ck, "difficulty", r.difficulty());
		ck.done();
		Scope s = scopes.of(projectId);
		CycleRow cycle = s.cycle(r.cycleId());
		checkPlanTimes(s, cycle, r.startAt(), r.deadline(), r.assigneeId(), true);
		Instant now = ops.now();
		UUID taskId = jdbc.sql("INSERT INTO tasks (project_id, created_by, created_at) VALUES (:p, :u, :at) RETURNING id")
				.param("p", projectId).param("u", user.id()).param("at", DbTime.toDb(now)).query(UUID.class).single();
		UUID roundId = jdbc.sql("""
				INSERT INTO task_review_rounds (project_id, task_id, round_no, cycle_id, reason, state, created_by, created_at)
				VALUES (:p, :t, 1, :c, 'INITIAL', 'DRAFT', :u, :at) RETURNING id
				""").param("p", projectId).param("t", taskId).param("c", cycle.id()).param("u", user.id())
				.param("at", DbTime.toDb(now)).query(UUID.class).single();
		UUID planId = jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, proposed_cycle_id, assignee_id, title,
				    description, expected_output, start_at, deadline, difficulty, status, created_by, approval_source,
				    created_at, updated_at)
				VALUES (:p, :r, 1, :c, :a, :title, :d, :o, :s, :dl, :df, 'DRAFT', :u, 'MEMBER_PLAN', :at, :at) RETURNING id
				""").param("p", projectId).param("r", roundId).param("c", cycle.id()).param("a", r.assigneeId())
				.param("title", r.title().strip()).param("d", r.description().strip()).param("o", r.expectedOutput().strip())
				.param("s", DbTime.toDb(r.startAt())).param("dl", DbTime.toDb(r.deadline())).param("df", r.difficulty())
				.param("u", user.id()).param("at", DbTime.toDb(now)).query(UUID.class).single();
		ops.history(projectId, user.id(), taskId, "TASK_CREATED", 1, s.name(user.id()) + " giao task \"" + r.title().strip()
				+ "\" cho " + s.name(r.assigneeId()), planId);
		ops.notify(projectId, "TASK_ASSIGNED", "TASK", taskId, cycle.id(), "task:" + taskId + ":assigned",
				List.of(r.assigneeId()), user.id(), "Bạn được giao task mới",
				"Task \"" + r.title().strip() + "\" đã được giao cho bạn. Hãy lập kế hoạch và gửi TL duyệt.",
				J.o("taskId", taskId, "groupId", projectId, "planId", planId));
		Ctx c = reader.require(roundId);
		return views.detail(s, c, new Viewer(user.id(), "LEAD"));
	}

	// ------------------------------------------------------------------ GET /tasks/{id}
	@Transactional(readOnly = true)
	public Map<String, Object> get(UUID taskId, CurrentUser user) {
		UUID projectId = reader.projectOfTask(taskId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task."));
		GroupMembership m = access.requireMember(projectId, user);
		Scope s = scopes.of(projectId);
		Ctx c = reader.require(reader.currentRoundOf(taskId).orElseThrow());
		return views.detail(s, c, viewer(m));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> history(UUID taskId, CurrentUser user, int page, int size) {
		UUID projectId = reader.projectOfTask(taskId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task."));
		access.requireMember(projectId, user);
		size = Math.min(Math.max(size, 1), 100);
		page = Math.max(page, 0);
		Scope s = scopes.of(projectId);
		String kinds = "'TASK_CREATED','PLAN_SAVED','PLAN_SUBMITTED','PLAN_WITHDRAWN','PLAN_APPROVED','PLAN_CHANGES_REQUESTED',"
				+ "'TASK_STARTED','PROGRESS_NOTE','RESULT_SUBMITTED','RESULT_ACCEPTED','RESULT_FIX_REQUIRED',"
				+ "'RESULT_UPDATE_REQUIRED','CARRIED_OVER','SUPPORT_HANDOFF','POINTS_AWARDED','POINTS_REVERSED'";
		long total = jdbc.sql("SELECT count(*) FROM audit_logs WHERE entity_type = 'TASK' AND entity_id = :t AND action IN (" + kinds + ")")
				.param("t", taskId).query(Long.class).single();
		List<Map<String, Object>> items = jdbc.sql("""
				SELECT id, occurred_at, actor_id, action, after_data->>'roundNo' AS round_no, after_data->>'summary' AS summary,
				       after_data->>'refId' AS ref_id
				  FROM audit_logs WHERE entity_type = 'TASK' AND entity_id = :t AND action IN (""" + kinds + """
				) ORDER BY occurred_at DESC, id LIMIT :lim OFFSET :off
				""").param("t", taskId).param("lim", size).param("off", page * size).query((rs, i) -> J.o("id", J.uuid(rs, "id"),
				"at", J.inst(rs, "occurred_at"), "actor", s.user(J.uuid(rs, "actor_id")), "kind", rs.getString("action"),
				"roundNo", rs.getString("round_no") == null ? null : Integer.valueOf(rs.getString("round_no")),
				"summary", rs.getString("summary"),
				"refId", rs.getString("ref_id") == null ? null : UUID.fromString(rs.getString("ref_id")))).list();
		return J.o("items", items, "page", page, "size", size, "totalItems", total, "totalPages", (total + size - 1) / size);
	}

	// ------------------------------------------------------------------ kế hoạch
	private record PlanCtx(Plan plan, Ctx ctx, Scope scope, GroupMembership member) {
	}

	private PlanCtx loadPlan(UUID planId, CurrentUser user) {
		Plan p = reader.plan(planId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy bản kế hoạch."));
		UUID projectId = jdbc.sql("SELECT project_id FROM task_review_rounds WHERE id = :r").param("r", p.roundId())
				.query(UUID.class).single();
		GroupMembership m = access.requireMember(projectId, user);
		ops.lockRound(p.roundId());
		// đọc lại sau khi khóa
		Plan fresh = reader.plan(planId).orElseThrow();
		return new PlanCtx(fresh, reader.require(p.roundId()), scopes.of(projectId), m);
	}

	private Map<String, Object> mutation(PlanCtx pc, UUID planId) {
		Ctx c = reader.require(pc.ctx().round().id());
		Plan p = reader.plan(planId).orElseThrow();
		return J.o("plan", views.plan(pc.scope(), p, c), "task", views.summary(pc.scope(), c, viewer(pc.member()), views.extras(List.of(c))));
	}

	private static void version(Long given, long actual) {
		if (given == null) {
			throw ApiException.validation("version", "Bắt buộc");
		}
		if (given != actual) {
			throw ApiException.versionConflict();
		}
	}

	@Transactional
	public Map<String, Object> updatePlan(UUID planId, CurrentUser user, UpdatePlanRequest r) {
		PlanCtx pc = loadPlan(planId, user);
		Plan p = pc.plan();
		Scope s = pc.scope();
		boolean lead = pc.member().isLeader();
		if (!List.of("DRAFT", "CHANGES_REQUESTED").contains(p.status())) {
			throw ApiException.of(HttpStatus.CONFLICT, "PLAN_NOT_EDITABLE", "Bản kế hoạch đang chờ duyệt hoặc đã duyệt, không thể sửa.");
		}
		boolean isAssignee = user.id().equals(p.assigneeId());
		if (!isAssignee && !(lead && "DRAFT".equals(p.status()))) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		J.Check ck = J.check();
		if (r.title() != null) {
			ck.len(r.title(), "title", 3, 120);
		}
		if (r.description() != null) {
			ck.len(r.description(), "description", 1, 4000);
		}
		if (r.expectedOutput() != null) {
			ck.len(r.expectedOutput(), "expectedOutput", 1, 2000);
		}
		if (r.difficulty() != null) {
			requireDifficulty(ck, "difficulty", r.difficulty());
		}
		ck.done();
		version(r.version(), p.version());
		if (r.assigneeId() != null && !r.assigneeId().equals(p.assigneeId())) {
			boolean hasEffective = pc.ctx().effective() != null;
			if (!lead || hasEffective) {
				throw ApiException.validation("assigneeId", "Chỉ TL được đổi người phụ trách khi chưa có kế hoạch được duyệt");
			}
		}
		UUID cycleId = r.cycleId() != null ? r.cycleId() : p.proposedCycleId();
		Instant start = r.startAt() != null ? r.startAt() : p.startAt();
		Instant deadline = r.deadline() != null ? r.deadline() : p.deadline();
		UUID assignee = r.assigneeId() != null ? r.assigneeId() : p.assigneeId();
		CycleRow cycle = s.cycle(cycleId);
		checkPlanTimes(s, cycle, start, deadline, r.assigneeId(), r.cycleId() != null);
		Instant now = ops.now();
		jdbc.sql("""
				UPDATE task_plan_revisions SET title = :title, description = :d, expected_output = :o, proposed_cycle_id = :c,
				       assignee_id = :a, start_at = :s, deadline = :dl, difficulty = :df, updated_at = :at, version = version + 1
				 WHERE id = :id
				""").param("title", r.title() != null ? r.title().strip() : p.title())
				.param("d", r.description() != null ? r.description().strip() : p.description())
				.param("o", r.expectedOutput() != null ? r.expectedOutput().strip() : p.expectedOutput())
				.param("c", cycleId).param("a", assignee).param("s", DbTime.toDb(start)).param("dl", DbTime.toDb(deadline))
				.param("df", r.difficulty() != null ? r.difficulty() : p.difficulty()).param("at", DbTime.toDb(now))
				.param("id", planId).update();
		// bản nháp chưa được duyệt: kỳ của vòng đi theo kỳ đề xuất
		if (pc.ctx().effective() == null && !cycleId.equals(pc.ctx().round().cycleId())) {
			jdbc.sql("UPDATE task_review_rounds SET cycle_id = :c WHERE id = :r").param("c", cycleId).param("r", p.roundId()).update();
		}
		ops.bumpTask(pc.ctx().round().taskId());
		ops.history(s.projectId(), user.id(), pc.ctx().round().taskId(), "PLAN_SAVED", pc.ctx().round().roundNo(),
				s.name(user.id()) + " lưu kế hoạch (bản " + p.revisionNo() + ")", planId);
		return mutation(pc, planId);
	}

	@Transactional
	public Map<String, Object> submitPlan(UUID planId, CurrentUser user, VersionedRequest r) {
		PlanCtx pc = loadPlan(planId, user);
		Plan p = pc.plan();
		Scope s = pc.scope();
		if (!user.id().equals(p.assigneeId())) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		if (!List.of("DRAFT", "CHANGES_REQUESTED").contains(p.status())) {
			throw ApiException.of(HttpStatus.CONFLICT, "INVALID_PLAN_STATE", "Bản kế hoạch không ở trạng thái có thể gửi duyệt.");
		}
		version(r.version(), p.version());
		List<ApiError.FieldError> missing = new ArrayList<>();
		if (p.assigneeId() == null) {
			missing.add(new ApiError.FieldError("assigneeId", "Thiếu người phụ trách"));
		}
		if (p.proposedCycleId() == null) {
			missing.add(new ApiError.FieldError("cycleId", "Thiếu kỳ"));
		}
		if (p.title() == null || p.title().isBlank()) {
			missing.add(new ApiError.FieldError("title", "Thiếu tên task"));
		}
		if (p.description() == null || p.description().isBlank()) {
			missing.add(new ApiError.FieldError("description", "Thiếu phạm vi công việc"));
		}
		if (p.expectedOutput() == null || p.expectedOutput().isBlank()) {
			missing.add(new ApiError.FieldError("expectedOutput", "Thiếu đầu ra mong đợi"));
		}
		if (p.startAt() == null) {
			missing.add(new ApiError.FieldError("startAt", "Thiếu giờ bắt đầu"));
		}
		if (p.deadline() == null) {
			missing.add(new ApiError.FieldError("deadline", "Thiếu deadline"));
		}
		if (p.difficulty() == null) {
			missing.add(new ApiError.FieldError("difficulty", "Thiếu độ khó"));
		}
		if (!missing.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_INCOMPLETE", "Kế hoạch chưa đủ thông tin để gửi duyệt.", missing);
		}
		CycleRow cycle = s.cycle(p.proposedCycleId());
		checkPlanTimes(s, cycle, p.startAt(), p.deadline(), null, true);
		Instant now = ops.now();
		jdbc.sql("""
				UPDATE task_plan_revisions SET status = 'PENDING_APPROVAL', submitted_by = :u, submitted_at = :at,
				       updated_at = :at, version = version + 1 WHERE id = :id
				""").param("u", user.id()).param("at", DbTime.toDb(now)).param("id", planId).update();
		notifyPlanSubmitted(s, pc.ctx(), p, user.id(), p.version() + 1);
		ops.bumpTask(pc.ctx().round().taskId());
		ops.history(s.projectId(), user.id(), pc.ctx().round().taskId(), "PLAN_SUBMITTED", pc.ctx().round().roundNo(),
				s.name(user.id()) + " gửi kế hoạch cho TL duyệt", planId);
		return mutation(pc, planId);
	}

	private void notifyPlanSubmitted(Scope s, Ctx c, Plan p, UUID actor, long version) {
		boolean change = c.effective() != null;
		UUID lead = s.leaderId();
		if (lead == null) {
			return;
		}
		ops.notify(s.projectId(), change ? "PLAN_CHANGE_SUBMITTED" : "PLAN_SUBMITTED", "PLAN_REVISION", p.id(), c.round().cycleId(),
				"plan:" + p.id() + ":submitted:" + version, List.of(lead), actor,
				change ? "Đề xuất thay đổi kế hoạch" : "Kế hoạch chờ duyệt",
				s.name(actor) + (change ? " đề xuất thay đổi kế hoạch task \"" : " gửi kế hoạch task \"") + p.title() + "\".",
				J.o("taskId", c.round().taskId(), "planId", p.id(), "groupId", s.projectId()));
	}

	@Transactional
	public Map<String, Object> withdrawPlan(UUID planId, CurrentUser user, VersionedRequest r) {
		PlanCtx pc = loadPlan(planId, user);
		Plan p = pc.plan();
		Scope s = pc.scope();
		if (!user.id().equals(p.submittedBy())) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		if (!"PENDING_APPROVAL".equals(p.status())) {
			throw ApiException.of(HttpStatus.CONFLICT, "INVALID_PLAN_STATE", "Chỉ rút lại được bản đang chờ duyệt.");
		}
		version(r.version(), p.version());
		jdbc.sql("""
				UPDATE task_plan_revisions SET status = 'DRAFT', submitted_by = NULL, submitted_at = NULL, updated_at = :at,
				       version = version + 1 WHERE id = :id
				""").param("at", DbTime.toDb(ops.now())).param("id", planId).update();
		ops.audit(s.projectId(), user.id(), "PLAN_REVISION", planId, "PLAN_WITHDRAWN", J.o("status", "PENDING_APPROVAL"),
				J.o("status", "DRAFT"));
		ops.bumpTask(pc.ctx().round().taskId());
		ops.history(s.projectId(), user.id(), pc.ctx().round().taskId(), "PLAN_WITHDRAWN", pc.ctx().round().roundNo(),
				s.name(user.id()) + " rút kế hoạch để sửa tiếp", planId);
		return mutation(pc, planId);
	}

	@Transactional
	public Map<String, Object> reviewPlan(UUID planId, CurrentUser user, ReviewPlanRequest r) {
		PlanCtx pc = loadPlan(planId, user);
		Plan p = pc.plan();
		Scope s = pc.scope();
		access.requireLeader(s.projectId(), user);
		J.Check ck = J.check().require("APPROVED".equals(r.decision()) || "CHANGES_REQUESTED".equals(r.decision()), "decision",
				"Quyết định phải là APPROVED hoặc CHANGES_REQUESTED");
		if ("CHANGES_REQUESTED".equals(r.decision())) {
			ck.len(r.note(), "note", 5, 1000);
		}
		ck.done();
		if (p.status().equals(r.decision()) && !"PENDING_APPROVAL".equals(p.status())) {
			return mutation(pc, planId); // lặp lại cùng quyết định -> kết quả hiện có
		}
		if (!"PENDING_APPROVAL".equals(p.status())) {
			throw ApiException.of(HttpStatus.CONFLICT, "INVALID_PLAN_STATE", "Bản kế hoạch không còn ở trạng thái chờ duyệt.");
		}
		version(r.version(), p.version());
		Ctx c = pc.ctx();
		if (p.baseRevisionId() != null && !p.baseRevisionId().equals(c.round().effectivePlanId())) {
			throw ApiException.versionConflict();
		}
		Instant now = ops.now();
		if ("APPROVED".equals(r.decision())) {
			CycleRow target = s.cycle(p.proposedCycleId());
			if (target.isClosed(now)) {
				throw ApiException.of(HttpStatus.CONFLICT, "CYCLE_CLOSED", "Kỳ của kế hoạch đã đóng, không thể duyệt vào kỳ này.");
			}
			jdbc.sql("""
					UPDATE task_plan_revisions SET status = 'APPROVED', reviewed_by = :u, reviewed_at = :at,
					       approval_source = COALESCE(approval_source, 'MEMBER_PLAN'), updated_at = :at, version = version + 1
					 WHERE id = :id
					""").param("u", user.id()).param("at", DbTime.toDb(now)).param("id", planId).update();
			jdbc.sql("""
					UPDATE task_review_rounds SET effective_plan_id = :p, cycle_id = :c,
					       state = CASE WHEN state = 'DRAFT' THEN 'ACTIVE' ELSE state END,
					       started_at = COALESCE(started_at, :at), version = version + 1 WHERE id = :r
					""").param("p", planId).param("c", p.proposedCycleId()).param("at", DbTime.toDb(now)).param("r", c.round().id()).update();
		}
		else {
			jdbc.sql("""
					UPDATE task_plan_revisions SET status = 'CHANGES_REQUESTED', reviewed_by = :u, reviewed_at = :at,
					       review_note = :n, updated_at = :at, version = version + 1 WHERE id = :id
					""").param("u", user.id()).param("at", DbTime.toDb(now)).param("n", r.note().strip()).param("id", planId).update();
		}
		boolean ok = "APPROVED".equals(r.decision());
		ops.audit(s.projectId(), user.id(), "PLAN_REVISION", planId, ok ? "PLAN_APPROVED" : "PLAN_CHANGES_REQUESTED",
				J.o("status", "PENDING_APPROVAL"), J.o("status", r.decision(), "note", r.note()));
		ops.bumpTask(c.round().taskId());
		ops.history(s.projectId(), user.id(), c.round().taskId(), ok ? "PLAN_APPROVED" : "PLAN_CHANGES_REQUESTED",
				c.round().roundNo(), ok ? s.name(user.id()) + " duyệt kế hoạch (bản " + p.revisionNo() + ")"
						: s.name(user.id()) + " yêu cầu chỉnh sửa kế hoạch", planId);
		ops.notify(s.projectId(), ok ? "PLAN_APPROVED" : "PLAN_CHANGES_REQUESTED", "PLAN_REVISION", planId, p.proposedCycleId(),
				"plan:" + planId + (ok ? ":approved" : ":changes-requested") + ":" + (p.version() + 1),
				List.of(p.assigneeId()), user.id(), ok ? "Kế hoạch đã được duyệt" : "Kế hoạch cần chỉnh sửa",
				ok ? "TL đã duyệt kế hoạch task \"" + p.title() + "\"." : "TL yêu cầu chỉnh sửa kế hoạch task \"" + p.title() + "\": " + r.note().strip(),
				J.o("taskId", c.round().taskId(), "planId", planId, "groupId", s.projectId()));
		return mutation(pc, planId);
	}

	@Transactional
	public Map<String, Object> proposeChange(UUID taskId, CurrentUser user, ProposeChangeRequest r) {
		UUID projectId = reader.projectOfTask(taskId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task."));
		GroupMembership m = access.requireMember(projectId, user);
		UUID roundId = reader.currentRoundOf(taskId).orElseThrow();
		ops.lockRound(roundId);
		Ctx c = reader.require(roundId);
		Scope s = scopes.of(projectId);
		if (!user.id().equals(c.executor())) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		Plan eff = c.effective();
		if (eff == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "NO_EFFECTIVE_PLAN", "Task chưa có kế hoạch được duyệt.");
		}
		J.Check ck = J.check().len(r.changeReason(), "changeReason", 5, 1000);
		if (r.title() != null) {
			ck.len(r.title(), "title", 3, 120);
		}
		if (r.description() != null) {
			ck.len(r.description(), "description", 1, 4000);
		}
		if (r.expectedOutput() != null) {
			ck.len(r.expectedOutput(), "expectedOutput", 1, 2000);
		}
		if (r.difficulty() != null) {
			requireDifficulty(ck, "difficulty", r.difficulty());
		}
		ck.done();
		if (!List.of("ACTIVE", "AWAITING_REVIEW").contains(c.round().state())) {
			throw ApiException.invalidState("Vòng xử lý hiện tại không cho đề xuất thay đổi.");
		}
		if (s.cycle(c.round().cycleId()).isClosed(s.now())) {
			throw ApiException.of(HttpStatus.CONFLICT, "CYCLE_CLOSED", "Kỳ của vòng đã đóng — dùng chuyển kỳ do TL chốt.");
		}
		if (c.open() != null) {
			throw ApiException.of(HttpStatus.CONFLICT, "REVISION_ALREADY_IN_FLIGHT", "Đã có bản sửa đang soạn hoặc chờ duyệt.");
		}
		String title = r.title() != null ? r.title().strip() : eff.title();
		String desc = r.description() != null ? r.description().strip() : eff.description();
		String out = r.expectedOutput() != null ? r.expectedOutput().strip() : eff.expectedOutput();
		UUID cycleId = r.cycleId() != null ? r.cycleId() : eff.proposedCycleId();
		UUID assignee = r.assigneeId() != null ? r.assigneeId() : eff.assigneeId();
		Instant start = r.startAt() != null ? r.startAt() : eff.startAt();
		Instant deadline = r.deadline() != null ? r.deadline() : eff.deadline();
		String diff = r.difficulty() != null ? r.difficulty() : eff.difficulty();
		boolean changed = !Objects.equals(title, eff.title()) || !Objects.equals(desc, eff.description())
				|| !Objects.equals(out, eff.expectedOutput()) || !Objects.equals(cycleId, eff.proposedCycleId())
				|| !Objects.equals(assignee, eff.assigneeId()) || !Objects.equals(start, eff.startAt())
				|| !Objects.equals(deadline, eff.deadline()) || !Objects.equals(diff, eff.difficulty());
		if (!changed) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CHANGES_PROPOSED", "Bản đề xuất không khác kế hoạch đang có hiệu lực.");
		}
		checkPlanTimes(s, s.cycle(cycleId), start, deadline, assignee.equals(eff.assigneeId()) ? null : assignee, true);
		boolean now = Boolean.TRUE.equals(r.submitNow());
		Instant at = ops.now();
		int nextNo = jdbc.sql("SELECT COALESCE(MAX(revision_no), 0) + 1 FROM task_plan_revisions WHERE round_id = :r")
				.param("r", roundId).query(Integer.class).single();
		UUID planId = jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, base_revision_id, proposed_cycle_id, assignee_id,
				    title, description, expected_output, start_at, deadline, difficulty, status, created_by, submitted_by, submitted_at,
				    approval_source, change_reason, created_at, updated_at)
				VALUES (:p, :r, :no, :base, :c, :a, :t, :d, :o, :s, :dl, :df, :st, :u, :sb, :sa, 'MEMBER_PLAN', :cr, :at, :at)
				RETURNING id
				""").param("p", projectId).param("r", roundId).param("no", nextNo).param("base", eff.id()).param("c", cycleId)
				.param("a", assignee).param("t", title).param("d", desc).param("o", out).param("s", DbTime.toDb(start))
				.param("dl", DbTime.toDb(deadline)).param("df", diff).param("st", now ? "PENDING_APPROVAL" : "DRAFT")
				.param("u", user.id()).param("sb", now ? user.id() : null).param("sa", now ? DbTime.toDb(at) : null)
				.param("cr", r.changeReason().strip()).param("at", DbTime.toDb(at)).query(UUID.class).single();
		ops.bumpTask(taskId);
		ops.history(projectId, user.id(), taskId, "PLAN_SAVED", c.round().roundNo(),
				s.name(user.id()) + " đề xuất thay đổi kế hoạch: " + r.changeReason().strip(), planId);
		PlanCtx pc = new PlanCtx(reader.plan(planId).orElseThrow(), reader.require(roundId), s, m);
		if (now) {
			notifyPlanSubmitted(s, pc.ctx(), pc.plan(), user.id(), pc.plan().version());
			ops.history(projectId, user.id(), taskId, "PLAN_SUBMITTED", c.round().roundNo(),
					s.name(user.id()) + " gửi đề xuất thay đổi cho TL duyệt", planId);
		}
		return mutation(pc, planId);
	}

	// ------------------------------------------------------------------ thực hiện
	@Transactional
	public Map<String, Object> start(UUID taskId, CurrentUser user, VersionedRequest r) {
		UUID projectId = reader.projectOfTask(taskId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task."));
		GroupMembership m = access.requireMember(projectId, user);
		UUID roundId = reader.currentRoundOf(taskId).orElseThrow();
		ops.lockRound(roundId);
		Ctx c = reader.require(roundId);
		Scope s = scopes.of(projectId);
		if (!user.id().equals(c.executor())) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		if (c.effective() == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "NO_EFFECTIVE_PLAN", "Task chưa có kế hoạch được duyệt.");
		}
		if (c.round().workStartedAt() == null) {
			version(r.version(), c.round().taskVersion());
			if (!"ACTIVE".equals(c.round().state()) || c.sub() != null) {
				throw ApiException.invalidState("Task đã nộp hoặc đã hoàn tất, không thể bắt đầu.");
			}
			jdbc.sql("UPDATE task_review_rounds SET work_started_at = :at, version = version + 1 WHERE id = :r")
					.param("at", DbTime.toDb(ops.now())).param("r", roundId).update();
			ops.bumpTask(taskId);
			ops.history(projectId, user.id(), taskId, "TASK_STARTED", c.round().roundNo(), s.name(user.id()) + " bắt đầu làm task", null);
		}
		else if (!List.of("ACTIVE", "AWAITING_REVIEW").contains(c.round().state())) {
			throw ApiException.invalidState("Task đã hoàn tất, không thể bắt đầu.");
		}
		Ctx fresh = reader.require(roundId);
		return views.summary(s, fresh, viewer(m), views.extras(List.of(fresh)));
	}

	@Transactional
	public Map<String, Object> progress(UUID taskId, CurrentUser user, ProgressUpdateRequest r) {
		UUID projectId = reader.projectOfTask(taskId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task."));
		access.requireMember(projectId, user);
		UUID roundId = reader.currentRoundOf(taskId).orElseThrow();
		ops.lockRound(roundId);
		Ctx c = reader.require(roundId);
		Scope s = scopes.of(projectId);
		if (!user.id().equals(c.executor())) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		if (c.effective() == null) {
			throw ApiException.of(HttpStatus.CONFLICT, "NO_EFFECTIVE_PLAN", "Task chưa có kế hoạch được duyệt.");
		}
		J.Check ck = J.check().len(r.note(), "note", 1, 1000);
		if (r.riskStatus() != null) {
			ck.require(RISKS.contains(r.riskStatus()), "riskStatus", "Giá trị không hợp lệ");
		}
		ck.done();
		Instant now = ops.now();
		UUID id = jdbc.sql("""
				INSERT INTO task_progress_notes (project_id, task_id, round_id, author_id, note, risk_status, expected_completion_at, created_at)
				VALUES (:p, :t, :r, :u, :n, :rs, :ec, :at) RETURNING id
				""").param("p", projectId).param("t", taskId).param("r", roundId).param("u", user.id()).param("n", r.note().strip())
				.param("rs", r.riskStatus()).param("ec", DbTime.toDb(r.expectedCompletionAt())).param("at", DbTime.toDb(now))
				.query(UUID.class).single();
		if (c.round().workStartedAt() == null) {
			jdbc.sql("UPDATE task_review_rounds SET work_started_at = :at WHERE id = :r").param("at", DbTime.toDb(now)).param("r", roundId).update();
		}
		ops.bumpTask(taskId);
		ops.history(projectId, user.id(), taskId, "PROGRESS_NOTE", c.round().roundNo(), s.name(user.id()) + " cập nhật tiến độ: " + r.note().strip(), id);
		return jdbc.sql("""
				SELECT id, task_id, author_id, note, risk_status, expected_completion_at, created_at FROM task_progress_notes WHERE id = :id
				""").param("id", id).query((rs, i) -> TaskViews.progressDto(s, rs)).single();
	}

	@Transactional
	public Map<String, Object> setIndependentReviewer(UUID taskId, CurrentUser user, SetIndependentReviewerRequest r) {
		UUID projectId = reader.projectOfTask(taskId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task."));
		GroupMembership m = access.requireLeader(projectId, user);
		UUID roundId = reader.currentRoundOf(taskId).orElseThrow();
		ops.lockRound(roundId);
		Ctx c = reader.require(roundId);
		Scope s = scopes.of(projectId);
		if ("ACCEPTED".equals(c.round().state())) {
			throw ApiException.of(HttpStatus.CONFLICT, "TASK_ALREADY_DONE", "Task đã hoàn thành.");
		}
		if (!Objects.equals(c.executor(), s.leaderId())) {
			throw ApiException.validation("reviewerUserId", "Chỉ chỉ định người duyệt độc lập cho task do chính TL thực hiện");
		}
		UUID rv = r.reviewerUserId();
		if (rv != null && (rv.equals(s.leaderId()) || !access.isActiveMember(projectId, rv))) {
			throw ApiException.validation("reviewerUserId", "Người duyệt phải là thành viên đang hoạt động, khác TL");
		}
		Instant now = ops.now();
		if (rv == null) {
			jdbc.sql("""
					UPDATE tasks SET independent_approver_id = NULL, independent_approver_set_by = NULL, independent_approver_set_at = NULL
					 WHERE id = :t
					""").param("t", taskId).update();
		}
		else {
			jdbc.sql("""
					UPDATE tasks SET independent_approver_id = :a, independent_approver_set_by = :u, independent_approver_set_at = :at
					 WHERE id = :t
					""").param("a", rv).param("u", user.id()).param("at", DbTime.toDb(now)).param("t", taskId).update();
		}
		ops.bumpTask(taskId);
		ops.audit(projectId, user.id(), "TASK", taskId, "INDEPENDENT_REVIEWER_SET", J.o("reviewer", c.round().independentApproverId()),
				J.o("reviewer", rv));
		return views.detail(s, reader.require(roundId), viewer(m));
	}

	CycleLifecycle lifecycle() {
		return lifecycle;
	}
}
