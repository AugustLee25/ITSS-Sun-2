package app.hanare.support;

import app.hanare.common.ApiError;
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
import app.hanare.support.SupportDtos.CreateRiskReportRequest;
import app.hanare.support.SupportDtos.ResolveRiskRequest;
import app.hanare.support.SupportDtos.RiskReportDto;
import java.sql.Array;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Risk chung (S03/S05/S07): chỉ chứa {@code publicSummary}. {@code DROPOUT_RISK} chỉ TL tạo và là {@code CONFIRMED} ngay
 * (điều kiện tô Đỏ). Báo risk không tự sửa deadline, không tự đánh dấu bỏ việc.
 */
@Service
public class RiskReportService {

	private static final Set<String> STATUSES = Set.of("OPEN", "CONFIRMED", "RESOLVED");

	private record Row(UUID id, UUID projectId, UUID taskId, UUID subjectId, UUID reportedBy, String riskType,
			String summary, String status, UUID confirmedBy, Instant confirmedAt, Instant resolvedAt, List<UUID> affected,
			Instant createdAt, long version) {
	}

	private static final String SELECT = """
			SELECT id, project_id, task_id, subject_member_id, reported_by, risk_type, public_summary, status, confirmed_by,
			       confirmed_at, resolved_at, affected_task_ids, created_at, version
			  FROM risk_reports
			""";

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public RiskReportService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, AuditWriter audit,
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
		List<UUID> affected = new ArrayList<>();
		Array arr = rs.getArray("affected_task_ids");
		if (arr != null) {
			for (Object o : (Object[]) arr.getArray()) {
				affected.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
			}
		}
		return new Row(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("task_id", UUID.class), rs.getObject("subject_member_id", UUID.class),
				rs.getObject("reported_by", UUID.class), rs.getString("risk_type"), rs.getString("public_summary"),
				rs.getString("status"), rs.getObject("confirmed_by", UUID.class), DbTime.instant(rs, "confirmed_at"),
				DbTime.instant(rs, "resolved_at"), affected, DbTime.instant(rs, "created_at"), rs.getLong("version"));
	}

	// ------------------------------------------------------------------------------------------------ tạo

	@Transactional
	public RiskReportDto create(UUID projectId, CurrentUser user, CreateRiskReportRequest req) {
		GroupMembership m = access.requireMember(projectId, user);
		boolean dropout = req.riskType() == SupportDtos.RiskType.DROPOUT_RISK;
		if (dropout && !m.isLeader()) {
			throw ApiException.of(ErrorCode.LEAD_ONLY, "Chỉ TL được xác nhận nguy cơ bỏ việc.");
		}
		TaskFacts task = null;
		if (req.taskId() != null) {
			task = tasks.facts(projectId, req.taskId()).orElseThrow(() -> ApiException.notFound("Không tìm thấy task."));
		}
		if (!dropout && !m.isLeader()) {
			// Member chỉ báo được cho task của chính mình
			if (task == null || !user.id().equals(tasks.executorOf(task))) {
				throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
			}
		}
		UUID subject = req.subjectUserId();
		if (subject == null) {
			if (dropout || task == null) {
				throw ApiException.validation("subjectUserId", "Cần chỉ rõ người liên quan.");
			}
			subject = tasks.executorOf(task);
		}
		else if (!dropout && !m.isLeader() && !user.id().equals(subject)) {
			throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		}
		if (subject == null || !access.isActiveMember(projectId, subject)) {
			throw ApiException.validation("subjectUserId", "Người liên quan phải là thành viên đang hoạt động của nhóm.");
		}
		List<UUID> affected = req.affectedTaskIds() == null ? List.of() : req.affectedTaskIds().stream().distinct().toList();
		Map<UUID, TaskFacts> affectedFacts = new LinkedHashMap<>();
		List<ApiError.FieldError> bad = new ArrayList<>();
		for (int i = 0; i < affected.size(); i++) {
			TaskFacts f = affected.get(i) == null ? null : tasks.facts(projectId, affected.get(i)).orElse(null);
			if (f == null) {
				bad.add(new ApiError.FieldError("affectedTaskIds[" + i + "]", "Task không thuộc nhóm."));
			}
			else {
				affectedFacts.put(f.taskId(), f);
			}
		}
		if (!bad.isEmpty()) {
			throw ApiException.validation(bad);
		}
		Instant now = clock.instant();
		String arrayLiteral = "{" + String.join(",", affected.stream().map(UUID::toString).toList()) + "}";
		UUID id = jdbc.sql("""
				INSERT INTO risk_reports (project_id, task_id, subject_member_id, reported_by, risk_type, public_summary,
				    status, confirmed_by, confirmed_at, affected_task_ids, created_at)
				VALUES (:p, :t, :s, :by, :rt, :sum, :st, :cb, :ca, CAST(:aff AS uuid[]), :at) RETURNING id
				""").param("p", projectId).param("t", req.taskId()).param("s", subject).param("by", user.id())
				.param("rt", req.riskType().name()).param("sum", req.publicSummary().trim())
				.param("st", dropout ? "CONFIRMED" : "OPEN").param("cb", dropout ? user.id() : null)
				.param("ca", dropout ? DbTime.toDb(now) : null).param("aff", arrayLiteral).param("at", DbTime.toDb(now))
				.query(UUID.class).single();
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("riskType", req.riskType().name());
		after.put("subjectUserId", subject);
		after.put("status", dropout ? "CONFIRMED" : "OPEN");
		audit.log(projectId, user.id(), "RISK_REPORT", id, dropout ? "DROPOUT_RISK_CONFIRMED" : "RISK_REPORTED", null, after);
		// RISK_RELEVANT: mỗi người thực hiện của task bị ảnh hưởng (trừ người báo); KHÔNG lộ nội dung riêng
		for (TaskFacts f : affectedFacts.values()) {
			UUID executor = tasks.executorOf(f);
			if (executor == null || executor.equals(user.id())) {
				continue;
			}
			notifications.publish(new NotificationEvent(projectId, "RISK_RELEVANT", "RISK_REPORT", id, null,
					"risk:" + id + ":relevant:" + f.taskId(), List.of(executor), "Có việc có thể ảnh hưởng tới task của bạn",
					"Một nguy cơ vừa được ghi nhận có thể ảnh hưởng tới task " + f.title() + ".",
					Map.of("riskReportId", id.toString(), "taskId", f.taskId().toString())));
		}
		return view(load(id));
	}

	// ------------------------------------------------------------------------------------------------ đọc / giải quyết

	@Transactional(readOnly = true)
	public PageOf<RiskReportDto> list(UUID projectId, CurrentUser user, String status, UUID taskId, Integer page,
			Integer size) {
		access.requireMember(projectId, user);
		int[] ps = Refs.pageArgs(page, size);
		if (status != null && !STATUSES.contains(status)) {
			throw ApiException.validation("status", "Giá trị không hợp lệ.");
		}
		StringBuilder where = new StringBuilder(" WHERE project_id = :p");
		Map<String, Object> params = new HashMap<>();
		params.put("p", projectId);
		if (status != null) {
			where.append(" AND status = :st");
			params.put("st", status);
		}
		if (taskId != null) {
			where.append(" AND (task_id = :t OR :t = ANY (affected_task_ids))");
			params.put("t", taskId);
		}
		long total = jdbc.sql("SELECT COUNT(*) FROM risk_reports" + where).params(params).query(Long.class).single();
		List<Row> rows = jdbc.sql(SELECT + where + " ORDER BY created_at DESC, id LIMIT :size OFFSET :off").params(params)
				.param("size", ps[1]).param("off", (long) ps[0] * ps[1]).query((rs, i) -> map(rs)).list();
		return PageOf.of(rows.stream().map(this::view).toList(), ps[0], ps[1], total);
	}

	@Transactional
	public RiskReportDto resolve(UUID riskId, CurrentUser user, ResolveRiskRequest req) {
		Row r0 = load(riskId);
		GroupMembership m = access.requireMember(r0.projectId(), user);
		if (!m.isLeader() && !user.id().equals(r0.reportedBy())) {
			throw ApiException.forbidden("Chỉ TL hoặc người đã báo mới giải quyết được nguy cơ này.");
		}
		Row r = jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", riskId).query((rs, i) -> map(rs)).single();
		if ("RESOLVED".equals(r.status())) {
			throw Err.conflict("RISK_ALREADY_RESOLVED", "Nguy cơ này đã được giải quyết.");
		}
		if (r.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		Instant now = clock.instant();
		jdbc.sql("""
				UPDATE risk_reports SET status = 'RESOLVED', resolved_at = :at, resolved_by = :by, resolve_note = :note,
				       version = version + 1 WHERE id = :id
				""").param("at", DbTime.toDb(now)).param("by", user.id())
				.param("note", req.note() == null || req.note().isBlank() ? null : req.note().trim()).param("id", riskId)
				.update();
		audit.log(r.projectId(), user.id(), "RISK_REPORT", riskId, "RISK_RESOLVED", Map.of("status", r.status()),
				Map.of("status", "RESOLVED"));
		return view(load(riskId));
	}

	private Row load(UUID id) {
		return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> map(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy báo nguy cơ."));
	}

	private RiskReportDto view(Row r) {
		return new RiskReportDto(r.id(), r.projectId(), r.taskId(), r.taskId() == null ? null : lookups.taskTitle(r.taskId()),
				lookups.user(r.subjectId()), lookups.user(r.reportedBy()), r.riskType(), r.summary(), r.status(),
				r.confirmedBy() == null ? null : lookups.user(r.confirmedBy()), r.confirmedAt(), r.resolvedAt(), r.affected(),
				r.createdAt(), r.version());
	}
}
