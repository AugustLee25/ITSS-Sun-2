package app.hanare.help;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.help.HelpDtos.CancelHelpRequestRequest;
import app.hanare.help.HelpDtos.CreateHelpRequestRequest;
import app.hanare.help.HelpDtos.HelpRequestCard;
import app.hanare.help.HelpDtos.HelpRequestDetail;
import app.hanare.help.HelpDtos.UpdateHelpRequestRequest;
import app.hanare.help.HelpViews.Req;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.AuditWriter;
import app.hanare.points.Err;
import app.hanare.points.IdempotencyKey;
import app.hanare.points.Lookups;
import app.hanare.points.Refs;
import app.hanare.points.Refs.PageOf;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.BudgetFacts;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Yêu cầu hỗ trợ CÔNG KHAI do TL đăng cho TOÀN BỘ task (spec S08/S09, 12.8.4): giá = đúng P_t đã khóa, giữ quỹ bằng một
 * reservation HELD (tối đa 1/task). {@code help_requests} chỉ chứa nội dung công khai do TL tự viết.
 */
@Service
public class HelpRequestService {

	private static final Set<String> TABS = Set.of("OPEN", "MY_OFFERS", "MY_WORK", "COMPLETED");
	private static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final HelpViews views;
	private final HandoffService handoff;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final SupportWorkService works;
	private final HelpNotices notices;
	private final Clock clock;

	public HelpRequestService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, HelpViews views,
			HandoffService handoff, AuditWriter audit, NotificationService notifications, SupportWorkService works,
			HelpNotices notices, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.views = views;
		this.handoff = handoff;
		this.audit = audit;
		this.notifications = notifications;
		this.works = works;
		this.notices = notices;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------------ đăng

	@Transactional
	public HelpRequestDetail create(UUID projectId, CurrentUser user, String idempotencyKey, CreateHelpRequestRequest req) {
		GroupMembership lead = access.requireLeader(projectId, user);
		IdempotencyKey.require(idempotencyKey);
		if (!Boolean.TRUE.equals(req.privacyChecked())) {
			throw ApiException.validation("privacyChecked", "TL phải xác nhận đã kiểm tra nội dung không lộ lý do riêng tư.");
		}
		// khóa task để tuần tự hóa đăng/duyệt/chọn người trên cùng một task
		jdbc.sql("SELECT id FROM tasks WHERE id = :t AND project_id = :p FOR UPDATE").param("t", req.taskId())
				.param("p", projectId).query(UUID.class).optional().orElseThrow(() -> ApiException.notFound("Không tìm thấy task."));
		TaskFacts f = tasks.facts(projectId, req.taskId()).orElseThrow(() -> ApiException.notFound("Không tìm thấy task."));
		Optional<String[]> existing = jdbc.sql("SELECT id::text, status FROM help_requests WHERE task_id = :t AND status <> 'CANCELLED'")
				.param("t", f.taskId()).query((rs, i) -> new String[] { rs.getString(1), rs.getString(2) }).optional();
		if (existing.isPresent()) {
			if ("COMPLETED".equals(existing.get()[1])) {
				throw Err.conflict("TASK_ALREADY_DONE", "Task đã được bàn giao và hoàn tất, không đăng lại.");
			}
			throw Err.conflict("HELP_REQUEST_ALREADY_OPEN", "Task này đã có yêu cầu hỗ trợ còn hiệu lực.");
		}
		handoff.requireTransferable(f);
		UUID executor = tasks.executorOf(f);
		if (!req.beneficiaryId().equals(executor)) {
			throw Err.unprocessable("BENEFICIARY_MISMATCH", "Người được hỗ trợ phải là người đang thực hiện task.");
		}
		UUID approver = lead.userId();
		if (req.beneficiaryId().equals(lead.userId())) {
			// task của chính TL: kết quả/điểm chỉ do người duyệt độc lập đã chỉ định duyệt (12.8.7.6)
			UUID independent = f.independentApproverId();
			if (independent == null || independent.equals(req.beneficiaryId()) || !access.isActiveMember(projectId, independent)) {
				throw Err.conflict("INDEPENDENT_REVIEWER_REQUIRED",
						"Task của TL cần người duyệt độc lập đã được chỉ định trước khi đăng yêu cầu hỗ trợ.");
			}
			approver = independent;
		}
		Optional<UUID> linked = Optional.empty();
		if (req.supportRequestId() != null) {
			UUID srId = req.supportRequestId();
			String status = jdbc.sql("SELECT status FROM support_requests WHERE id = :id AND project_id = :p AND task_id = :t")
					.param("id", srId).param("p", projectId).param("t", f.taskId()).query(String.class).optional()
					.orElseThrow(() -> ApiException.notFound("Không tìm thấy đề nghị hỗ trợ riêng của task này."));
			if ("RESOLVED".equals(status)) {
				throw Err.conflict("SUPPORT_REQUEST_CLOSED", "Đề nghị riêng này đã được xử lý xong.");
			}
			linked = Optional.of(srId);
		}
		Optional<BudgetFacts> budget = tasks.budget(f.taskId()).filter(b -> "FUNDED".equals(b.state()));
		if (budget.isPresent()) {
			BudgetFacts b = budget.get();
			if (b.awarded()) {
				throw Err.conflict("TASK_ALREADY_DONE", "Task đã được ghi điểm.");
			}
			if (!b.recipientId().equals(req.beneficiaryId())) {
				throw Err.conflict("TASK_NOT_TRANSFERABLE", "Quyền nhận điểm của task không còn thuộc người được hỗ trợ.");
			}
			if (b.held()) {
				throw Err.conflict("HELP_REQUEST_ALREADY_OPEN", "Quỹ điểm của task đang được giữ cho một yêu cầu khác.");
			}
		}
		Instant now = clock.instant();
		String expected = firstNonBlank(req.expectedOutput(), f.expectedOutput(), req.description());
		UUID id = jdbc.sql("""
				INSERT INTO help_requests (project_id, support_request_id, task_id, round_id, beneficiary_id, result_approver_id,
				    created_by, title, description, expected_output, difficulty, deadline, effort_estimate_minutes, skill_note,
				    task_point_budget_id, advertised_point_units, created_at, updated_at)
				VALUES (:p, :sr, :t, :r, :ben, :apr, :by, :title, :desc, :exp, :diff, :dl, :eff, :skill, :budget, :units, :at, :at)
				RETURNING id
				""").param("p", projectId).param("sr", linked.orElse(null)).param("t", f.taskId()).param("r", f.roundId())
				.param("ben", req.beneficiaryId()).param("apr", approver).param("by", lead.userId())
				.param("title", req.title().trim()).param("desc", req.description().trim()).param("exp", expected)
				.param("diff", f.difficulty()).param("dl", DbTime.toDb(f.deadline()))
				.param("eff", positiveOrNull(req.estimatedEffortMinutes(), "estimatedEffortMinutes"))
				.param("skill", blankToNull(req.skillNotes())).param("budget", budget.map(BudgetFacts::budgetId).orElse(null))
				.param("units", budget.map(BudgetFacts::units).orElse(null)).param("at", DbTime.toDb(now)).query(UUID.class)
				.single();
		if (budget.isPresent()) {
			jdbc.sql("INSERT INTO task_point_reservations (budget_id, help_request_id, point_units, created_at, updated_at) VALUES (:b, :h, :u, :at, :at)")
					.param("b", budget.get().budgetId()).param("h", id).param("u", budget.get().units())
					.param("at", DbTime.toDb(now)).update();
		}
		if (linked.isPresent()) {
			jdbc.sql("""
					UPDATE support_requests SET status = 'RESOLVED', decision = 'HELP_PUBLISHED',
					       reviewed_by = :by, reviewed_at = :at, updated_at = :at, version = version + 1 WHERE id = :id
					""").param("by", lead.userId()).param("at", DbTime.toDb(now)).param("id", linked.get()).update();
		}
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("taskId", f.taskId());
		after.put("beneficiaryId", req.beneficiaryId());
		after.put("units", budget.map(BudgetFacts::units).orElse(null));
		audit.log(projectId, lead.userId(), "HELP_REQUEST", id, "HELP_REQUEST_PUBLISHED", null, after);
		notices.helpPublished(projectId, id, f.title(), f.difficulty(), budget.map(BudgetFacts::units).orElse(null), lead.userId());
		if (linked.isPresent()) {
			UUID sender = jdbc.sql("SELECT requester_id FROM support_requests WHERE id = :id").param("id", linked.get())
					.query(UUID.class).single();
			notifications.publish(new NotificationEvent(projectId, "SUPPORT_DECISION", "SUPPORT_REQUEST", linked.get(), null,
					"support:" + linked.get() + ":help-published:" + id, List.of(sender), "TL đã phản hồi đề nghị của bạn",
					"TL đã đăng phần việc của bạn để cả nhóm cùng hỗ trợ.",
					Map.of("supportRequestId", linked.get().toString(), "taskId", f.taskId().toString())));
		}
		return views.detail(views.req(id), lead.userId(), true);
	}

	// ------------------------------------------------------------------------------------------------ đọc

	@Transactional
	public PageOf<HelpRequestCard> list(UUID projectId, CurrentUser user, String tab, String difficulty, java.time.Instant from,
			java.time.Instant to, Long minUnits, Long maxUnits, Integer maxEffort, Integer page, Integer size) {
		access.requireMember(projectId, user);
		int[] ps = Refs.pageArgs(page, size);
		String t = tab == null ? "OPEN" : tab;
		if (!TABS.contains(t)) {
			throw ApiException.validation("tab", "Giá trị không hợp lệ.");
		}
		if (difficulty != null && !DIFFICULTIES.contains(difficulty)) {
			throw ApiException.validation("difficulty", "Giá trị không hợp lệ.");
		}
		works.syncProject(projectId);
		StringBuilder where = new StringBuilder(" WHERE h.project_id = :p");
		Map<String, Object> params = new HashMap<>();
		params.put("p", projectId);
		params.put("u", user.id());
		switch (t) {
			case "OPEN" -> where.append(" AND h.status = 'OPEN'");
			case "MY_OFFERS" -> where.append(" AND EXISTS (SELECT 1 FROM support_offers o WHERE o.help_request_id = h.id AND o.helper_id = :u)");
			case "MY_WORK" -> where.append(" AND EXISTS (SELECT 1 FROM support_works w WHERE w.help_request_id = h.id AND w.helper_id = :u AND w.status <> 'APPROVED')");
			default -> where.append(" AND h.status IN ('COMPLETED', 'CANCELLED')");
		}
		if (difficulty != null) {
			where.append(" AND h.difficulty = :d");
			params.put("d", difficulty);
		}
		if (from != null) {
			where.append(" AND h.deadline >= :from");
			params.put("from", DbTime.toDb(from));
		}
		if (to != null) {
			where.append(" AND h.deadline <= :to");
			params.put("to", DbTime.toDb(to));
		}
		if (minUnits != null) {
			where.append(" AND h.advertised_point_units >= :minU");
			params.put("minU", minUnits);
		}
		if (maxUnits != null) {
			where.append(" AND h.advertised_point_units <= :maxU");
			params.put("maxU", maxUnits);
		}
		if (maxEffort != null) {
			where.append(" AND h.effort_estimate_minutes <= :eff");
			params.put("eff", maxEffort);
		}
		long total = jdbc.sql("SELECT COUNT(*) FROM help_requests h" + where).params(params).query(Long.class).single();
		List<Req> rows = jdbc.sql(HelpViews.REQ_SELECT_H + where + " ORDER BY h.deadline, h.id LIMIT :size OFFSET :off")
				.params(params).param("size", ps[1]).param("off", (long) ps[0] * ps[1]).query((rs, i) -> HelpViews.mapReq(rs))
				.list();
		List<HelpRequestCard> items = new ArrayList<>();
		for (Req r : rows) {
			items.add(views.card(r, user.id()));
		}
		return PageOf.of(items, ps[0], ps[1], total);
	}

	@Transactional
	public HelpRequestDetail get(UUID helpRequestId, CurrentUser user) {
		Req r = views.req(helpRequestId);
		GroupMembership m = access.requireMember(r.projectId(), user);
		works.syncProject(r.projectId());
		return views.detail(views.req(helpRequestId), user.id(), m.isLeader());
	}

	// ------------------------------------------------------------------------------------------------ sửa / hủy

	@Transactional
	public HelpRequestDetail update(UUID helpRequestId, CurrentUser user, UpdateHelpRequestRequest req) {
		Req r0 = views.req(helpRequestId);
		GroupMembership lead = access.requireLeader(r0.projectId(), user);
		Req r = views.reqForUpdate(helpRequestId);
		requireRequester(r, lead);
		if (!"OPEN".equals(r.status())) {
			throw Err.conflict("HELP_REQUEST_NOT_OPEN", "Yêu cầu không còn mở nên không sửa được.");
		}
		if (r.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		String title = req.title() != null ? req.title().trim() : r.title();
		String desc = req.description() != null ? req.description().trim() : r.description();
		String expected = req.expectedOutput() != null ? firstNonBlank(req.expectedOutput(), r.expectedOutput()) : r.expectedOutput();
		Integer effort = req.estimatedEffortMinutes() != null ? positiveOrNull(req.estimatedEffortMinutes(), "estimatedEffortMinutes")
				: r.effort();
		String skill = req.skillNotes() != null ? blankToNull(req.skillNotes()) : r.skillNote();
		boolean important = !Objects.equals(desc, r.description()) || !Objects.equals(expected, r.expectedOutput());
		Instant now = clock.instant();
		jdbc.sql("""
				UPDATE help_requests SET title = :t, description = :d, expected_output = :e, effort_estimate_minutes = :f,
				       skill_note = :s, updated_at = :at, version = version + 1 WHERE id = :id
				""").param("t", title).param("d", desc).param("e", expected).param("f", effort).param("s", skill)
				.param("at", DbTime.toDb(now)).param("id", helpRequestId).update();
		if (important) {
			jdbc.sql("UPDATE support_offers SET needs_reconfirmation = true WHERE help_request_id = :h AND status = 'PENDING'")
					.param("h", helpRequestId).update();
		}
		audit.log(r.projectId(), lead.userId(), "HELP_REQUEST", helpRequestId, "HELP_REQUEST_UPDATED", null,
				Map.of("importantChange", important));
		return views.detail(views.req(helpRequestId), lead.userId(), true);
	}

	@Transactional
	public HelpRequestDetail cancel(UUID helpRequestId, CurrentUser user, CancelHelpRequestRequest req) {
		Req r0 = views.req(helpRequestId);
		GroupMembership lead = access.requireLeader(r0.projectId(), user);
		Req r = views.reqForUpdate(helpRequestId);
		requireRequester(r, lead);
		if ("CANCELLED".equals(r.status())) {
			return views.detail(r, lead.userId(), true);
		}
		if (!"OPEN".equals(r.status())) {
			if ("COMPLETED".equals(r.status())) {
				throw Err.conflict("HELP_REQUEST_NOT_OPEN", "Yêu cầu đã hoàn tất.");
			}
			throw Err.conflict("HELP_REQUEST_HAS_HELPER",
					"Đã có người nhận phần việc; hãy xử lý bàn giao qua phần việc hỗ trợ (báo không thể hoàn thành / giải quyết).");
		}
		if (r.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		Instant now = clock.instant();
		jdbc.sql("""
				UPDATE help_requests SET status = 'CANCELLED', cancelled_at = :at, cancel_reason = :reason, updated_at = :at,
				       version = version + 1 WHERE id = :id
				""").param("at", DbTime.toDb(now)).param("reason", blankToNull(req.reason())).param("id", helpRequestId).update();
		jdbc.sql("UPDATE task_point_reservations SET state = 'RELEASED', updated_at = :at WHERE help_request_id = :h AND state = 'HELD'")
				.param("at", DbTime.toDb(now)).param("h", helpRequestId).update();
		List<UUID> pending = jdbc.sql("SELECT helper_id FROM support_offers WHERE help_request_id = :h AND status = 'PENDING' ORDER BY created_at")
				.param("h", helpRequestId).query(UUID.class).list();
		jdbc.sql("""
				UPDATE support_offers SET status = 'NOT_SELECTED', decided_at = :at, decided_by = :by, needs_reconfirmation = false
				 WHERE help_request_id = :h AND status = 'PENDING'
				""").param("at", DbTime.toDb(now)).param("by", lead.userId()).param("h", helpRequestId).update();
		audit.log(r.projectId(), lead.userId(), "HELP_REQUEST", helpRequestId, "HELP_REQUEST_CANCELLED", null,
				Map.of("offersNotSelected", pending.size()));
		if (!pending.isEmpty()) {
			notifications.publish(new NotificationEvent(r.projectId(), "SUPPORT_NOT_SELECTED", "HELP_REQUEST", helpRequestId,
					null, "help:" + helpRequestId + ":cancelled", pending, "Cảm ơn bạn đã sẵn lòng hỗ trợ",
					"Yêu cầu hỗ trợ \"" + r.title() + "\" đã được đóng. Cảm ơn bạn đã sẵn lòng giúp nhóm.",
					Map.of("helpRequestId", helpRequestId.toString())));
		}
		return views.detail(views.req(helpRequestId), lead.userId(), true);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private void requireRequester(Req r, GroupMembership lead) {
		if (!lead.userId().equals(r.createdBy())) {
			throw ApiException.of(app.hanare.common.ErrorCode.LEAD_ONLY, "Chỉ người đăng yêu cầu được thao tác.");
		}
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) {
				return v.trim();
			}
		}
		return null;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static Integer positiveOrNull(Integer v, String field) {
		if (v == null) {
			return null;
		}
		if (v < 1 || v > 100_000) {
			throw ApiException.validation(field, "Phải trong khoảng 1–100000.");
		}
		return v;
	}
}
