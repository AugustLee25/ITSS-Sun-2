package app.hanare.support;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.AuditWriter;
import app.hanare.points.Err;
import app.hanare.points.Lookups;
import app.hanare.points.Refs;
import app.hanare.points.Refs.PageOf;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.support.SupportDtos.CreateSupportRequestRequest;
import app.hanare.support.SupportDtos.SupportRequestDecisionRequest;
import app.hanare.support.SupportDtos.SupportRequestDto;
import app.hanare.support.SupportDtos.SupportRequestSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đề nghị hỗ trợ RIÊNG Member → TL (spec S07). Chỉ người gửi và TL đọc được; nội dung riêng không bao giờ vào thông báo,
 * dashboard hay yêu cầu công khai.
 */
@Service
public class SupportRequestService {

	private static final Set<String> STATUSES = Set.of("OPEN", "IN_REVIEW", "RESOLVED");

	private record Row(UUID id, UUID projectId, UUID taskId, UUID roundId, UUID requesterId, String obstacleType,
			String description, String helpNeeded, String impact, LocalDate expectedDate, String status, String decision,
			String leadNote, UUID reviewedBy, Instant reviewedAt, Instant createdAt, Instant updatedAt, long version) {
	}

	private static final String SELECT = """
			SELECT id, project_id, task_id, round_id, requester_id, obstacle_type, description, help_needed, impact,
			       expected_date, status, decision, lead_note, reviewed_by, reviewed_at, created_at, updated_at, version
			  FROM support_requests
			""";

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public SupportRequestService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, AuditWriter audit,
			NotificationService notifications, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
	}

	private Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
		java.sql.Date d = rs.getDate("expected_date");
		return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("task_id", UUID.class), rs.getObject("round_id", UUID.class),
				rs.getObject("requester_id", UUID.class), rs.getString("obstacle_type"), rs.getString("description"),
				rs.getString("help_needed"), rs.getString("impact"), d == null ? null : d.toLocalDate(),
				rs.getString("status"), rs.getString("decision"), rs.getString("lead_note"),
				rs.getObject("reviewed_by", UUID.class), DbTime.instant(rs, "reviewed_at"), DbTime.instant(rs, "created_at"),
				DbTime.instant(rs, "updated_at"), rs.getLong("version"));
	}

	// ------------------------------------------------------------------------------------------------ tạo

	@Transactional
	public SupportRequestDto create(UUID taskId, CurrentUser user, CreateSupportRequestRequest req) {
		TaskFacts f = tasks.facts(taskId).orElseThrow(() -> ApiException.notFound("Không tìm thấy task."));
		GroupMembership m = access.requireMember(f.projectId(), user);
		if (m.isLeader()) {
			throw ApiException.of(ErrorCode.SELF_ACTION_FORBIDDEN,
					"TL không gửi đề nghị hỗ trợ riêng cho chính mình; hãy đăng yêu cầu công khai khi cần.");
		}
		if (!user.id().equals(tasks.executorOf(f))) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		UUID roundId = f.roundId();
		if (req.roundId() != null) {
			boolean ok = jdbc.sql("SELECT EXISTS (SELECT 1 FROM task_review_rounds WHERE id = :r AND task_id = :t)")
					.param("r", req.roundId()).param("t", taskId).query(Boolean.class).single();
			if (!ok) {
				throw ApiException.validation("roundId", "Vòng không thuộc task này.");
			}
			roundId = req.roundId();
		}
		Instant now = clock.instant();
		UUID id = jdbc.sql("""
				INSERT INTO support_requests (project_id, task_id, round_id, requester_id, obstacle_type, description,
				    help_needed, impact, expected_date, created_at, updated_at)
				VALUES (:p, :t, :r, :u, :o, :d, :h, :i, :e, :at, :at) RETURNING id
				""").param("p", f.projectId()).param("t", taskId).param("r", roundId).param("u", user.id())
				.param("o", req.obstacleType().name()).param("d", req.description().trim())
				.param("h", req.neededSupport().trim()).param("i", blankToNull(req.impact())).param("e", req.expectedDate())
				.param("at", DbTime.toDb(now)).query(UUID.class).single();
		audit.log(f.projectId(), user.id(), "SUPPORT_REQUEST", id, "SUPPORT_REQUEST_CREATED", null,
				Map.of("taskId", taskId));
		// thông báo CHỈ cho TL và KHÔNG chứa nội dung riêng (mô tả, loại trở ngại, phần cần hỗ trợ)
		access.activeLeaderId(f.projectId()).ifPresent(lead -> notifications.publish(new NotificationEvent(f.projectId(),
				"NEED_HELP", "SUPPORT_REQUEST", id, null, "support:" + id + ":need-help", List.of(lead),
				"Có đề nghị hỗ trợ mới", lookups.userName(user.id()) + " vừa gửi đề nghị hỗ trợ riêng cho task " + f.title() + ".",
				Map.of("supportRequestId", id.toString(), "taskId", taskId.toString()))));
		return view(load(id), m);
	}

	// ------------------------------------------------------------------------------------------------ đọc

	@Transactional(readOnly = true)
	public PageOf<SupportRequestSummary> list(UUID projectId, CurrentUser user, String status, UUID taskId, String scope,
			Integer page, Integer size) {
		GroupMembership m = access.requireMember(projectId, user);
		int[] ps = Refs.pageArgs(page, size);
		if (status != null && !STATUSES.contains(status)) {
			throw ApiException.validation("status", "Giá trị không hợp lệ.");
		}
		if (scope != null && !Set.of("ALL", "MINE").contains(scope)) {
			throw ApiException.validation("scope", "Giá trị không hợp lệ.");
		}
		boolean mine = !m.isLeader() || "MINE".equals(scope);
		StringBuilder where = new StringBuilder(" WHERE project_id = :p");
		Map<String, Object> params = new java.util.HashMap<>();
		params.put("p", projectId);
		if (mine) {
			where.append(" AND requester_id = :u");
			params.put("u", user.id());
		}
		if (status != null) {
			where.append(" AND status = :st");
			params.put("st", status);
		}
		if (taskId != null) {
			where.append(" AND task_id = :t");
			params.put("t", taskId);
		}
		long total = jdbc.sql("SELECT COUNT(*) FROM support_requests" + where).params(params).query(Long.class).single();
		List<Row> rows = jdbc.sql(SELECT + where + " ORDER BY created_at DESC, id LIMIT :size OFFSET :off").params(params)
				.param("size", ps[1]).param("off", (long) ps[0] * ps[1]).query((rs, i) -> map(rs)).list();
		List<SupportRequestSummary> items = new ArrayList<>();
		for (Row r : rows) {
			items.add(summary(r));
		}
		return PageOf.of(items, ps[0], ps[1], total);
	}

	@Transactional(readOnly = true)
	public SupportRequestDto get(UUID id, CurrentUser user) {
		Row r = load(id);
		GroupMembership m = access.requireMember(r.projectId(), user);
		requireReadable(r, m);
		return view(r, m);
	}

	// ------------------------------------------------------------------------------------------------ TL xử lý

	@Transactional
	public SupportRequestDto decide(UUID id, CurrentUser user, SupportRequestDecisionRequest req) {
		Row r0 = load(id);
		GroupMembership m = access.requireLeader(r0.projectId(), user);
		Row r = jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query((rs, i) -> map(rs)).single();
		if ("RESOLVED".equals(r.status())) {
			throw Err.conflict("SUPPORT_REQUEST_CLOSED", "Đề nghị này đã được xử lý xong.");
		}
		if (r.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		Instant now = clock.instant();
		boolean resolve = req.action() == SupportDtos.SupportRequestLeadAction.RESOLVED_PRIVATELY;
		jdbc.sql("""
				UPDATE support_requests SET status = :st, decision = :dec, lead_note = :note, reviewed_by = :by,
				       reviewed_at = :at, updated_at = :at, version = version + 1 WHERE id = :id
				""").param("st", resolve ? "RESOLVED" : "IN_REVIEW").param("dec", resolve ? "RESOLVED_PRIVATELY" : null)
				.param("note", req.response().trim()).param("by", m.userId()).param("at", DbTime.toDb(now)).param("id", id)
				.update();
		audit.log(r.projectId(), m.userId(), "SUPPORT_REQUEST", id, resolve ? "SUPPORT_REQUEST_RESOLVED" : "SUPPORT_REQUEST_DISCUSS",
				Map.of("status", r.status()), Map.of("status", resolve ? "RESOLVED" : "IN_REVIEW"));
		notifications.publish(new NotificationEvent(r.projectId(), "SUPPORT_DECISION", "SUPPORT_REQUEST", id, null,
				"support:" + id + ":decision:" + (r.version() + 1), List.of(r.requesterId()), "TL đã phản hồi đề nghị của bạn",
				resolve ? "TL đã xử lý đề nghị hỗ trợ của bạn, không cần đăng yêu cầu công khai."
						: "TL muốn trao đổi thêm về đề nghị hỗ trợ của bạn.",
				Map.of("supportRequestId", id.toString(), "taskId", r.taskId().toString())));
		return view(load(id), m);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private Row load(UUID id) {
		return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> map(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy đề nghị hỗ trợ."));
	}

	private void requireReadable(Row r, GroupMembership m) {
		if (!m.isLeader() && !m.userId().equals(r.requesterId())) {
			throw ApiException.forbidden("Đề nghị hỗ trợ riêng chỉ người gửi và TL xem được.");
		}
	}

	private SupportRequestSummary summary(Row r) {
		return new SupportRequestSummary(r.id(), r.taskId(), lookups.taskTitle(r.taskId()), lookups.user(r.requesterId()),
				r.obstacleType(), r.status(), r.createdAt(), r.updatedAt());
	}

	private SupportRequestDto view(Row r, GroupMembership viewer) {
		List<UUID> published = jdbc.sql("SELECT id FROM help_requests WHERE support_request_id = :id ORDER BY created_at")
				.param("id", r.id()).query(UUID.class).list();
		List<String> actions = viewer.isLeader() && !"RESOLVED".equals(r.status()) ? List.of("DECIDE", "PUBLISH_HELP")
				: List.of();
		return new SupportRequestDto(r.id(), r.taskId(), lookups.taskTitle(r.taskId()), lookups.user(r.requesterId()),
				r.obstacleType(), r.status(), r.createdAt(), r.updatedAt(), r.projectId(), r.roundId(), r.description(),
				r.helpNeeded(), r.impact(), r.expectedDate(), r.decision(), r.leadNote(),
				r.reviewedBy() == null ? null : lookups.user(r.reviewedBy()), r.reviewedAt(), published, r.version(), actions);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}
}
