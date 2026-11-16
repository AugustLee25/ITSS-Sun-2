package app.hanare.dashboard;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.task.CycleData;
import app.hanare.task.CycleData.Reports;
import app.hanare.task.CycleData.Row;
import app.hanare.task.CycleLifecycle;
import app.hanare.task.J;
import app.hanare.task.Scope;
import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Eval;
import app.hanare.task.TaskViews;
import app.hanare.task.TaskViews.Auth;
import app.hanare.task.TaskViews.Extras;
import app.hanare.task.TaskViews.Viewer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mục D: dashboard S03 (header, GroupHealth, việc của tôi, checkpoint, khung tên XANH/VÀNG/ĐỎ/XÁM, hàng đợi TL, hỗ trợ,
 * điểm). Dữ liệu support/help/points đọc bằng SQL chỉ-đọc (bảng có thể rỗng).
 */
@Service
public class DashboardService {

	private record Member(UUID id, String role) {
	}

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Scope.Factory scopes;
	private final TaskReader reader;
	private final TaskViews views;
	private final CycleData data;
	private final CycleLifecycle lifecycle;
	private final JsonMapper mapper;

	public DashboardService(JdbcClient jdbc, GroupAccess access, Scope.Factory scopes, TaskReader reader, TaskViews views,
			CycleData data, CycleLifecycle lifecycle, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.access = access;
		this.scopes = scopes;
		this.reader = reader;
		this.views = views;
		this.data = data;
		this.lifecycle = lifecycle;
		this.mapper = mapper;
	}

	private CycleRow pickCycle(Scope s, UUID cycleId) {
		if (cycleId != null) {
			return s.cycleOpt(cycleId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint trong nhóm."));
		}
		return s.containing(s.now()).or(() -> s.allCycles().stream().filter(c -> !s.now().isBefore(c.closesAt()))
				.reduce((a, b) -> b)).or(() -> s.allCycles().stream().findFirst())
				.orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Nhóm chưa có kỳ checkpoint."));
	}

	private List<Member> members(UUID projectId) {
		return jdbc.sql("""
				SELECT m.user_id, m.role FROM project_members m JOIN users u ON u.id = m.user_id
				 WHERE m.project_id = :p AND m.left_at IS NULL ORDER BY (m.role = 'LEAD') DESC, u.name, u.id
				""").param("p", projectId).query((rs, i) -> new Member(J.uuid(rs, "user_id"), rs.getString("role"))).list();
	}

	// ------------------------------------------------------------------ GET /groups/{id}/dashboard
	@Transactional(readOnly = true)
	public Map<String, Object> dashboard(UUID projectId, UUID cycleId, CurrentUser user) {
		GroupMembership gm = access.requireMember(projectId, user);
		Scope s = scopes.of(projectId);
		CycleRow cy = pickCycle(s, cycleId);
		Viewer v = new Viewer(user.id(), gm.role().name());
		Instant now = s.now();
		UUID me = user.id();
		List<Member> members = members(projectId);
		List<Row> rows = data.rows(s, cy);
		Reports rep = data.reports(cy);
		Map<UUID, CycleData.Dropout> dropouts = data.dropouts(projectId);
		Map<UUID, Ctx> all = reader.loadAll(reader.currentRoundIds(projectId));
		Extras x = views.extras(all.values());

		// khung tên thành viên
		List<Map<String, Object>> progress = new ArrayList<>();
		int reported = 0;
		boolean missingNeeded = false;
		for (Member mem : members) {
			List<Row> mine = rows.stream().filter(r -> r.assigneeId().equals(mem.id())).toList();
			progress.add(progress(s, cy, mem, mine, rep, dropouts.get(mem.id())));
			if (rep.reportOf().containsKey(mem.id())) {
				reported++;
			}
			else if (!mine.isEmpty()) {
				missingNeeded = true;
			}
		}
		int notResponded = members.size() - reported;

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("serverTime", J.t(now));
		m.put("group", header(s, members, all, x, cy, missingNeeded, rows.isEmpty()));
		m.put("myRole", gm.role().name());
		m.put("cycle", s.cycleDto(cy));
		m.put("previousCycleId", cy.previousCycleId());
		m.put("nextCycleId", s.next(cy).map(CycleRow::id).orElse(null));
		m.put("myActions", myActions(s, cy, v, all, x, rows, rep));
		List<Ctx> mineCtx = all.values().stream().filter(c -> me.equals(c.executor()) && !List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state()))
				.sorted(Comparator.comparing((Ctx c) -> c.deadline() == null ? Instant.MAX : c.deadline())).limit(5).toList();
		m.put("myTasks", mineCtx.stream().map(c -> views.summary(s, c, v, x)).toList());
		List<Row> myRows = rows.stream().filter(r -> r.assigneeId().equals(me)).toList();
		long myResponded = myRows.stream().filter(r -> rep.item(me, r.roundId()) != null).count();
		String myState = rep.state(me);
		m.put("nextCheckpoint", J.o("cycle", s.cycleDto(cy), "myRequiredTaskCount", myRows.size(), "myRespondedTaskCount", myResponded,
				"myReportState", myState, "myReportLabel", reportLabel(myState, myResponded == myRows.size()),
				"canReportNow", cy.reportWindowOpen(now), "reportedMemberCount", reported, "notRespondedMemberCount", notResponded));
		m.put("memberProgress", progress);
		m.put("leadPanel", gm.isLeader() ? leadPanel(s, cy, v, all, x) : null);
		m.put("sharedRisks", sharedRisks(projectId));
		m.put("openHelp", openHelp(s, me));
		m.put("myPoints", myPoints(projectId, me));
		m.put("recentActivity", recent(s));
		m.put("flags", J.o("groupHasAnyTask", all.values().stream().anyMatch(Ctx::committed) || !all.isEmpty(),
				"iHaveAnyTask", all.values().stream().anyMatch(c -> me.equals(c.executor()))));
		return m;
	}

	private String reportLabel(String state, boolean complete) {
		return switch (state) {
			case "SUBMITTED" -> complete ? "Đã gửi đầy đủ" : "Đã gửi, còn thiếu task";
			case "DRAFT" -> "Đã lưu nháp";
			default -> "Chưa có cập nhật";
		};
	}

	private Map<String, Object> progress(Scope s, CycleRow cy, Member mem, List<Row> mine, Reports rep, CycleData.Dropout drop) {
		Map<String, Object> eval = data.evaluation(s, cy, mine, rep, mem.id(), drop, false);
		Map<String, Object> cutoff = null;
		if (data.frozen(cy.id())) {
			String json = jdbc.sql("SELECT cutoff_summary::text FROM checkpoint_participants WHERE cycle_id = :c AND member_id = :m")
					.param("c", cy.id()).param("m", mem.id()).query(String.class).optional().orElse(null);
			if (json != null && json.length() > 2) {
				@SuppressWarnings("unchecked")
				Map<String, Object> parsed = mapper.readValue(json, Map.class);
				cutoff = parsed;
			}
		}
		String state = rep.state(mem.id());
		boolean complete = mine.stream().allMatch(r -> rep.item(mem.id(), r.roundId()) != null);
		Map<String, Object> dropout = drop == null ? null : J.o("riskReportId", drop.riskId(), "confirmedBy", s.user(drop.confirmedBy()),
				"confirmedAt", drop.confirmedAt(), "publicSummary", drop.summary());
		return J.o("member", s.user(mem.id()), "role", mem.role(), "evaluation", eval, "cutoffEvaluation", cutoff, "reportState", state,
				"reportLabel", reportLabel(state, complete), "reportSubmittedAt", rep.submittedAt().get(mem.id()), "dropoutRisk", dropout);
	}

	// ------------------------------------------------------------------ header + GroupHealth (4.3)
	private Map<String, Object> header(Scope s, List<Member> members, Map<UUID, Ctx> all, Extras x, CycleRow cy,
			boolean missingNeeded, boolean noRowsInCycle) {
		Map<String, Object> g = jdbc.sql("SELECT name, course_name, deadline FROM projects WHERE id = :p").param("p", s.projectId())
				.query((rs, i) -> J.o("name", rs.getString("name"), "courseName", rs.getString("course_name"),
						"deadline", J.inst(rs, "deadline").atZone(s.tz()).toLocalDate().toString())).single();
		long openRisks = jdbc.sql("SELECT count(*) FROM risk_reports WHERE project_id = :p AND status IN ('OPEN', 'CONFIRMED')")
				.param("p", s.projectId()).query(Long.class).single();
		boolean blocked = false;
		boolean atRisk = false;
		boolean anyCommitted = false;
		for (Ctx c : all.values()) {
			anyCommitted |= c.committed();
			if (List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state())) {
				continue;
			}
			String risk = x.risk().getOrDefault(c.round().id(), "NORMAL");
			blocked |= "BLOCKED".equals(risk);
			atRisk |= "AT_RISK".equals(risk) && cy.id().equals(c.round().cycleId());
		}
		String health;
		String reason;
		if (blocked || openRisks > 0 || atRisk) {
			health = "NEEDS_ATTENTION";
			reason = (openRisks > 0 ? openRisks + " risk đang mở ảnh hưởng tiến độ" : blocked ? "Có task đang bị chặn" : "Có task có nguy cơ trễ")
					+ "; chưa phải kết luận về năng suất cá nhân.";
		}
		else if (!anyCommitted || (missingNeeded && s.now().isAfter(cy.reminderAt()) && !"FINALIZED".equals(cy.status()))) {
			health = "INSUFFICIENT_INFO";
			reason = !anyCommitted ? "Nhóm chưa có task nào được duyệt kế hoạch." : "Còn thành viên chưa có cập nhật checkpoint; không đồng nghĩa với trễ.";
		}
		else {
			health = "ON_TRACK";
			reason = "Chưa ghi nhận rủi ro hay thiếu cập nhật cần xử lý.";
		}
		UUID lead = s.leaderId();
		return J.o("id", s.projectId(), "name", g.get("name"), "courseName", g.get("courseName"), "deadline", g.get("deadline"),
				"memberCount", members.size(), "leader", s.user(lead), "health", health,
				"healthLabel", switch (health) {
					case "NEEDS_ATTENTION" -> "Cần xem xét";
					case "INSUFFICIENT_INFO" -> "Chưa đủ thông tin";
					default -> "Theo kế hoạch";
				}, "healthReason", reason);
	}

	// ------------------------------------------------------------------ hành động của tôi
	private List<Map<String, Object>> myActions(Scope s, CycleRow cy, Viewer v, Map<UUID, Ctx> all, Extras x, List<Row> rows, Reports rep) {
		UUID me = v.id();
		UUID g = s.projectId();
		List<Map<String, Object>> a = new ArrayList<>();
		List<Row> myRows = rows.stream().filter(r -> r.assigneeId().equals(me)).toList();
		long missing = myRows.stream().filter(r -> rep.item(me, r.roundId()) == null).count();
		if (cy.reportWindowOpen(s.now()) && !myRows.isEmpty() && (missing > 0 || !"SUBMITTED".equals(rep.state(me)))) {
			a.add(action("CHECKPOINT_REPORT", "Cập nhật checkpoint Kỳ " + cy.sequenceNo(), "Chốt " + s.instantLabel(cy.closesAt()),
					cy.closesAt(), (int) Math.max(missing, 1), "S06", J.o("groupId", g, "cycleId", cy.id())));
		}
		List<Ctx> draft = all.values().stream().filter(c -> c.open() != null && me.equals(c.open().assigneeId())
				&& List.of("DRAFT", "CHANGES_REQUESTED").contains(c.open().status())).toList();
		if (!draft.isEmpty()) {
			a.add(action("COMPLETE_PLAN", "Hoàn thiện kế hoạch task", draft.get(0).open().title(), null, draft.size(), "S05",
					J.o("groupId", g, "taskId", draft.get(0).round().taskId(), "planId", draft.get(0).open().id())));
		}
		if (v.lead()) {
			List<Ctx> pend = all.values().stream().filter(c -> c.open() != null && "PENDING_APPROVAL".equals(c.open().status())).toList();
			if (!pend.isEmpty()) {
				a.add(action("REVIEW_PLAN", "Duyệt kế hoạch", pend.size() + " kế hoạch đang chờ", null, pend.size(), "S05",
						J.o("groupId", g, "taskId", pend.get(0).round().taskId(), "planId", pend.get(0).open().id())));
			}
		}
		UUID reviewCycle = null;
		int reviewable = 0;
		int carry = 0;
		for (Ctx c : all.values()) {
			CycleRow rc = s.cycle(c.round().cycleId());
			if (!s.now().isAfter(rc.closesAt()) || List.of("ACCEPTED", "CARRIED_OVER").contains(c.round().state()) || !c.committed()) {
				continue;
			}
			Eval e = TaskReader.eval(c, c.deadline(), s.now(), rc.closesAt(), false);
			if (c.sub() != null && c.rev() == null && TaskViews.reviewAuth(s, c, v, x.points().get(c.round().taskId())) == Auth.OK) {
				reviewable++;
				reviewCycle = rc.id();
			}
			if (v.lead() && e.needsCarryover()) {
				carry++;
				reviewCycle = reviewCycle == null ? rc.id() : reviewCycle;
			}
		}
		if (reviewable > 0) {
			a.add(action("REVIEW_RESULT", "Duyệt kết quả sau checkpoint", reviewable + " task chờ duyệt", null, reviewable, "S03",
					J.o("groupId", g, "cycleId", reviewCycle)));
		}
		if (carry > 0) {
			a.add(action("SET_CARRYOVER", "Chốt deadline xử lý tiếp", "Cần TL chốt lịch xử lý tiếp", null, carry, "S03",
					J.o("groupId", g, "cycleId", reviewCycle)));
		}
		long fix = all.values().stream().filter(c -> me.equals(c.executor()) && List.of("FIX_REQUIRED", "UPDATE_REQUIRED").contains(c.round().reason())
				&& "ACTIVE".equals(c.round().state()) && c.sub() == null).count();
		if (fix > 0) {
			Ctx first = all.values().stream().filter(c -> me.equals(c.executor()) && List.of("FIX_REQUIRED", "UPDATE_REQUIRED").contains(c.round().reason())
					&& "ACTIVE".equals(c.round().state())).findFirst().get();
			a.add(action("FIX_TASK", "Sửa/cập nhật task theo yêu cầu của TL", first.display().title(), first.deadline(), (int) fix, "S05",
					J.o("groupId", g, "taskId", first.round().taskId())));
		}
		if (v.lead()) {
			var sr = jdbc.sql("SELECT id FROM support_requests WHERE project_id = :p AND status IN ('OPEN', 'IN_REVIEW') ORDER BY created_at")
					.param("p", g).query(UUID.class).list();
			if (!sr.isEmpty()) {
				a.add(action("REVIEW_SUPPORT_REQUEST", "Xem đề nghị hỗ trợ riêng", sr.size() + " đề nghị chưa xử lý", null, sr.size(), "S07",
						J.o("groupId", g, "supportRequestId", sr.get(0).toString())));
			}
			var offers = jdbc.sql("""
					SELECT h.id FROM help_requests h WHERE h.project_id = :p AND h.created_by = :m AND h.status = 'OPEN'
					   AND EXISTS (SELECT 1 FROM support_offers o WHERE o.help_request_id = h.id AND o.status = 'PENDING')
					""").param("p", g).param("m", me).query(UUID.class).list();
			if (!offers.isEmpty()) {
				a.add(action("REVIEW_SUPPORT_OFFERS", "Chọn người hỗ trợ", offers.size() + " yêu cầu có đề nghị mới", null, offers.size(), "S09",
						J.o("groupId", g, "helpRequestId", offers.get(0).toString())));
			}
		}
		var results = jdbc.sql("SELECT id FROM support_works WHERE project_id = :p AND result_approver_id = :m AND status = 'SUBMITTED'")
				.param("p", g).param("m", me).query(UUID.class).list();
		if (!results.isEmpty()) {
			a.add(action("REVIEW_SUPPORT_RESULT", "Duyệt kết quả làm thay", results.size() + " kết quả chờ duyệt", null, results.size(), "S11",
					J.o("groupId", g, "workId", results.get(0).toString())));
		}
		var cont = jdbc.sql("SELECT id FROM support_works WHERE project_id = :p AND helper_id = :m AND status IN ('IN_PROGRESS', 'REVISION_REQUESTED')")
				.param("p", g).param("m", me).query(UUID.class).list();
		if (!cont.isEmpty()) {
			a.add(action("CONTINUE_SUPPORT_WORK", "Tiếp tục công việc làm thay", cont.size() + " công việc", null, cont.size(), "S10",
					J.o("groupId", g, "workId", cont.get(0).toString())));
		}
		return a;
	}

	private static Map<String, Object> action(String kind, String title, String subtitle, Instant dueAt, int count, String screen,
			Map<String, Object> params) {
		Map<String, Object> p = new LinkedHashMap<>();
		params.forEach((k, val) -> p.put(k, String.valueOf(val)));
		return J.o("kind", kind, "title", title, "subtitle", subtitle, "dueAt", dueAt, "count", count, "target",
				J.o("screen", screen, "params", p));
	}

	// ------------------------------------------------------------------ khu TL
	private Map<String, Object> leadPanel(Scope s, CycleRow cy, Viewer v, Map<UUID, Ctx> all, Extras x) {
		List<Ctx> pend = all.values().stream().filter(c -> c.open() != null && "PENDING_APPROVAL".equals(c.open().status()))
				.sorted(Comparator.comparing(c -> c.open().submittedAt())).toList();
		List<Map<String, Object>> plans = pend.stream().limit(10).map(c -> views.toPlanQueue(s, c.open(), c)).toList();
		List<Map<String, Object>> support = jdbc.sql("""
				SELECT id, task_id, requester_id, obstacle_type, status, created_at, updated_at FROM support_requests
				 WHERE project_id = :p AND status IN ('OPEN', 'IN_REVIEW') ORDER BY created_at LIMIT 5
				""").param("p", s.projectId()).query((rs, i) -> {
			UUID task = J.uuid(rs, "task_id");
			Ctx c = reader.currentRoundOf(task).flatMap(reader::load).orElse(null);
			return J.o("id", J.uuid(rs, "id"), "taskId", task, "taskTitle", c == null || c.display() == null ? "" : c.display().title(),
					"sender", s.user(J.uuid(rs, "requester_id")), "obstacleType", rs.getString("obstacle_type"),
					"status", rs.getString("status"), "createdAt", J.inst(rs, "created_at"), "updatedAt", J.inst(rs, "updated_at"));
		}).list();
		long supportCount = jdbc.sql("SELECT count(*) FROM support_requests WHERE project_id = :p AND status IN ('OPEN', 'IN_REVIEW')")
				.param("p", s.projectId()).query(Long.class).single();
		Optional<CycleRow> review = Optional.of(cy).filter(c -> s.now().isAfter(c.closesAt()) && !"FINALIZED".equals(c.status()))
				.or(() -> s.allCycles().stream().filter(c -> s.now().isAfter(c.closesAt()) && !"FINALIZED".equals(c.status())
						&& !"CANCELLED".equals(c.status())).reduce((a, b) -> b));
		Map<String, Object> summary = review.map(c -> lifecycle.leadSummary(s.projectId(), c.id(), false)).orElse(null);
		int awaiting = 0;
		int carry = 0;
		if (review.isPresent()) {
			for (Row r : data.rows(s, review.get())) {
				Eval e = data.eval(s, review.get(), r);
				if (e.needsCarryover()) {
					carry++;
				}
				else if (r.ctx().sub() != null && r.ctx().rev() == null && !List.of("ACCEPTED", "CARRIED_OVER").contains(r.ctx().round().state())) {
					awaiting++;
				}
			}
		}
		return J.o("pendingPlans", J.o("count", pend.size(), "items", plans), "privateSupportRequests",
				J.o("count", supportCount, "items", support), "reviewCycle", summary, "awaitingReviewCount", awaiting,
				"carryoverRequiredCount", carry);
	}

	// ------------------------------------------------------------------ risk, hỗ trợ, điểm, hoạt động (SQL chỉ-đọc)
	private List<Map<String, Object>> sharedRisks(UUID projectId) {
		return jdbc.sql("""
				SELECT id, risk_type, task_id, public_summary, created_at FROM risk_reports
				 WHERE project_id = :p AND status IN ('OPEN', 'CONFIRMED') ORDER BY created_at DESC LIMIT 5
				""").param("p", projectId).query((rs, i) -> {
			UUID task = J.uuid(rs, "task_id");
			String title = null;
			if (task != null) {
				Ctx c = reader.currentRoundOf(task).flatMap(reader::load).orElse(null);
				title = c == null || c.display() == null ? null : c.display().title();
			}
			return J.o("id", J.uuid(rs, "id"), "riskType", rs.getString("risk_type"), "taskId", task, "taskTitle", title,
					"publicSummary", rs.getString("public_summary"), "createdAt", J.inst(rs, "created_at"));
		}).list();
	}

	private List<Map<String, Object>> openHelp(Scope s, UUID me) {
		return jdbc.sql("""
				SELECT h.id, h.title, h.description, h.expected_output, h.task_id, h.created_by, h.beneficiary_id, h.deadline,
				       h.difficulty, h.effort_estimate_minutes, h.advertised_point_units, h.status, h.created_at,
				       (SELECT o.status FROM support_offers o WHERE o.help_request_id = h.id AND o.helper_id = :m
				         ORDER BY o.created_at DESC LIMIT 1) AS my_offer,
				       (SELECT w.id FROM support_works w WHERE w.help_request_id = h.id LIMIT 1) AS work_id
				  FROM help_requests h WHERE h.project_id = :p AND h.status = 'OPEN' ORDER BY h.created_at DESC LIMIT 3
				""").param("p", s.projectId()).param("m", me).query((rs, i) -> {
			UUID task = J.uuid(rs, "task_id");
			Ctx c = reader.currentRoundOf(task).flatMap(reader::load).orElse(null);
			String desc = rs.getString("description");
			Long units = rs.getObject("advertised_point_units", Long.class);
			String diff = rs.getString("difficulty");
			UUID by = J.uuid(rs, "created_by");
			UUID ben = J.uuid(rs, "beneficiary_id");
			return J.o("id", J.uuid(rs, "id"), "groupId", s.projectId(), "title", rs.getString("title"),
					"descriptionPreview", desc.length() > 200 ? desc.substring(0, 200) : desc,
					"expectedOutput", rs.getString("expected_output"),
					"task", J.o("id", task, "title", c == null || c.display() == null ? rs.getString("title") : c.display().title()),
					"postedBy", s.user(by), "beneficiary", s.user(ben), "deadline", J.inst(rs, "deadline"), "difficulty", diff,
					"estimatedEffortMinutes", rs.getObject("effort_estimate_minutes", Integer.class),
					"points", units == null ? null : J.amount(units),
					"pointLabel", units == null ? null : TaskViews.difficultyLabel(diff) + " · +" + J.percentLabel(units) + " điểm % khi TL duyệt",
					"status", rs.getString("status"), "statusLabel", "Đang tìm người nhận làm thay toàn bộ task",
					"myRoleInRequest", me.equals(by) ? "REQUESTER" : me.equals(ben) ? "BENEFICIARY" : "VIEWER",
					"myOfferStatus", rs.getString("my_offer"), "workId", J.uuid(rs, "work_id"), "createdAt", J.inst(rs, "created_at"));
		}).list();
	}

	private Map<String, Object> myPoints(UUID projectId, UUID me) {
		boolean active = jdbc.sql("SELECT EXISTS (SELECT 1 FROM point_policies WHERE project_id = :p AND status = 'ACTIVE')")
				.param("p", projectId).query(Boolean.class).single();
		if (!active) {
			return J.o("policyActive", false, "confirmedPoints", null, "potentialPoints", null, "label", null);
		}
		long confirmed = jdbc.sql("SELECT COALESCE(SUM(delta_units), 0) FROM point_ledger_entries WHERE project_id = :p AND recipient_id = :m")
				.param("p", projectId).param("m", me).query(Long.class).single();
		long potential = jdbc.sql("""
				SELECT COALESCE(SUM(b.point_units), 0) FROM task_point_budgets b
				  JOIN task_point_assignments a ON a.budget_id = b.id AND a.status = 'CURRENT'
				 WHERE b.project_id = :p AND b.state = 'FUNDED' AND a.recipient_id = :m
				""").param("p", projectId).param("m", me).query(Long.class).single();
		return J.o("policyActive", true, "confirmedPoints", J.amount(confirmed), "potentialPoints", J.amount(potential),
				"label", "Đã xác nhận " + J.percentLabel(confirmed) + "% / Có thể đạt " + J.percentLabel(potential) + "%");
	}

	private List<Map<String, Object>> recent(Scope s) {
		return jdbc.sql("""
				SELECT id, occurred_at, actor_id, entity_type, entity_id, action, after_data->>'summary' AS summary,
				       after_data->>'cycleId' AS cycle_id
				  FROM audit_logs WHERE project_id = :p AND ((entity_type = 'TASK' AND action IN ('TASK_CREATED','PLAN_SUBMITTED',
				       'PLAN_APPROVED','PLAN_CHANGES_REQUESTED','RESULT_SUBMITTED','RESULT_ACCEPTED','RESULT_FIX_REQUIRED',
				       'RESULT_UPDATE_REQUIRED','CARRIED_OVER','SUPPORT_HANDOFF','POINTS_AWARDED'))
				       OR (entity_type = 'CHECKPOINT_REPORT' AND action = 'REPORT_SUBMITTED'))
				 ORDER BY occurred_at DESC, id LIMIT 5
				""").param("p", s.projectId()).query((rs, i) -> {
			boolean report = "CHECKPOINT_REPORT".equals(rs.getString("entity_type"));
			Map<String, Object> target = report && rs.getString("cycle_id") != null
					? J.o("screen", "S06", "params", J.o("groupId", s.projectId().toString(), "cycleId", rs.getString("cycle_id")))
					: report ? null : J.o("screen", "S05", "params", J.o("groupId", s.projectId().toString(), "taskId", J.uuid(rs, "entity_id").toString()));
			return J.o("id", J.uuid(rs, "id"), "at", J.inst(rs, "occurred_at"),
					"text", report ? s.name(J.uuid(rs, "actor_id")) + " đã gửi báo cáo checkpoint" : rs.getString("summary"), "target", target);
		}).list();
	}

	// ------------------------------------------------------------------ GET /groups/{id}/dashboard/members/{userId}
	@Transactional(readOnly = true)
	public Map<String, Object> memberDetail(UUID projectId, UUID userId, UUID cycleId, CurrentUser user) {
		GroupMembership gm = access.requireMember(projectId, user);
		Scope s = scopes.of(projectId);
		Member mem = members(projectId).stream().filter(x -> x.id().equals(userId)).findFirst()
				.orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Thành viên không thuộc nhóm."));
		CycleRow cy = pickCycle(s, cycleId);
		List<Row> mine = data.rows(s, cy).stream().filter(r -> r.assigneeId().equals(userId)).toList();
		Reports rep = data.reports(cy);
		boolean note = gm.isLeader() || user.id().equals(userId);
		List<Map<String, Object>> tasks = mine.stream().map(r -> data.taskRow(s, cy, r, rep, note)).toList();
		return J.o("cycle", s.cycleDto(cy), "progress", progress(s, cy, mem, mine, rep, data.dropouts(projectId).get(userId)), "tasks", tasks);
	}
}
