package app.hanare.notification;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.group.PageParams;
import app.hanare.group.PageResponse;
import app.hanare.notification.NotificationDtos.MarkAllReadRequest;
import app.hanare.notification.NotificationDtos.MarkAllReadResponse;
import app.hanare.notification.NotificationDtos.NotificationItem;
import app.hanare.notification.NotificationDtos.OpenNotificationResponse;
import app.hanare.notification.NotificationDtos.ScreenTarget;
import app.hanare.notification.NotificationDtos.UnreadByGroup;
import app.hanare.notification.NotificationDtos.UnreadCountResponse;
import app.hanare.security.CurrentUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Đọc/đánh dấu thông báo của người dùng hiện tại (mục E). Chỉ thay đổi trạng thái đọc, không đồng nghĩa đã xử lý. */
@Service
class NotificationQueryService {

	private static final String FROM = """
			  FROM notifications n
			  JOIN notification_events e ON e.id = n.event_id
			  JOIN projects p ON p.id = n.project_id
			  JOIN project_members m ON m.project_id = n.project_id AND m.user_id = n.recipient_id AND m.left_at IS NULL
			""";

	private final JdbcClient jdbc;
	private final Clock clock;
	private final JsonMapper json;
	private final NotificationTargets targets;

	NotificationQueryService(JdbcClient jdbc, Clock clock, JsonMapper json, NotificationTargets targets) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.json = json;
		this.targets = targets;
	}

	private NotificationItem item(ResultSet rs, int i) throws SQLException {
		NotificationType type = NotificationType.valueOf(rs.getString("event_type"));
		UUID projectId = rs.getObject("project_id", UUID.class);
		String entityType = rs.getString("entity_type");
		UUID entityId = rs.getObject("entity_id", UUID.class);
		UUID cycleId = rs.getObject("cycle_id", UUID.class);
		JsonNode payload = json.readTree(rs.getString("payload"));
		JsonNode subject = payload.get("subjectLabel");
		Instant readAt = DbTime.instant(rs, "read_at");
		return new NotificationItem(rs.getObject("id", UUID.class), type.name(), type.priority().name(), projectId,
				rs.getString("group_name"), rs.getString("title"), rs.getString("message"),
				subject == null || subject.isNull() ? null : subject.asString(), entityType, entityId, cycleId,
				NotificationTargets.target(type, projectId, entityType, entityId, cycleId, payload), readAt != null, readAt,
				DbTime.instant(rs, "created_at"));
	}

	private static final String ITEM_COLUMNS = """
			SELECT n.id, n.project_id, p.name AS group_name, n.title, n.message, n.read_at, n.created_at,
			       e.event_type, e.entity_type, e.entity_id, e.cycle_id, CAST(e.payload AS text) AS payload
			""";

	private static String tabClause(String tab) {
		return switch (tab) {
			case "ACTION_REQUIRED" -> " AND e.event_type IN (:actionTypes)";
			case "INFO" -> " AND e.event_type NOT IN (:actionTypes)";
			default -> "";
		};
	}

	private static String parseTab(String tab) {
		if (tab == null || tab.isBlank()) {
			return "ALL";
		}
		if (!tab.equals("ACTION_REQUIRED") && !tab.equals("INFO") && !tab.equals("ALL")) {
			throw ApiException.validation("tab", "Tab phải là ACTION_REQUIRED, INFO hoặc ALL.");
		}
		return tab;
	}

	@Transactional(readOnly = true)
	PageResponse<NotificationItem> list(CurrentUser user, UUID groupId, String tabParam, Boolean unreadOnly, Integer page,
			Integer size) {
		String tab = parseTab(tabParam);
		PageParams params = PageParams.of(page, size);
		String where = " WHERE n.recipient_id = :me" + (groupId != null ? " AND n.project_id = :group" : "")
				+ (Boolean.TRUE.equals(unreadOnly) ? " AND n.read_at IS NULL" : "") + tabClause(tab);
		long total = bind(jdbc.sql("SELECT count(*)" + FROM + where), user, groupId, tab).query(Long.class).single();
		List<NotificationItem> items = bind(jdbc.sql(ITEM_COLUMNS + FROM + where
				+ " ORDER BY (n.read_at IS NULL) DESC, n.created_at DESC, n.id LIMIT :limit OFFSET :offset"), user, groupId,
				tab).param("limit", params.size()).param("offset", params.offset()).query(this::item).list();
		return PageResponse.of(items, params, total);
	}

	private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, CurrentUser user, UUID groupId, String tab) {
		spec = spec.param("me", user.id());
		if (groupId != null) {
			spec = spec.param("group", groupId);
		}
		if (!tab.equals("ALL")) {
			spec = spec.param("actionTypes", NotificationType.actionRequiredNames());
		}
		return spec;
	}

	@Transactional(readOnly = true)
	UnreadCountResponse unreadCount(CurrentUser user, UUID groupId) {
		JdbcClient.StatementSpec spec = jdbc.sql("""
				SELECT n.project_id AS group_id, count(*) AS total,
				       count(*) FILTER (WHERE e.event_type IN (:actionTypes)) AS action_required
				""" + FROM + " WHERE n.recipient_id = :me AND n.read_at IS NULL"
				+ (groupId != null ? " AND n.project_id = :group" : "") + " GROUP BY n.project_id ORDER BY n.project_id")
				.param("me", user.id()).param("actionTypes", NotificationType.actionRequiredNames());
		if (groupId != null) {
			spec = spec.param("group", groupId);
		}
		long total = 0;
		long action = 0;
		List<UnreadByGroup> byGroup = new ArrayList<>();
		List<Object[]> rows = spec.query((rs, i) -> new Object[] { rs.getObject("group_id", UUID.class),
				rs.getLong("total"), rs.getLong("action_required") }).list();
		for (Object[] r : rows) {
			byGroup.add(new UnreadByGroup((UUID) r[0], (Long) r[1]));
			total += (Long) r[1];
			action += (Long) r[2];
		}
		return new UnreadCountResponse(total, action, byGroup);
	}

	private NotificationItem find(UUID notificationId, UUID userId) {
		return jdbc.sql(ITEM_COLUMNS + FROM + " WHERE n.id = :id AND n.recipient_id = :me")
				.param("id", notificationId).param("me", userId).query(this::item).optional()
				.orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy thông báo."));
	}

	/** Đánh dấu đã đọc (lặp lại không đổi). Thông báo của người khác → 404. */
	@Transactional
	NotificationItem markRead(CurrentUser user, UUID notificationId) {
		find(notificationId, user.id());
		jdbc.sql("UPDATE notifications SET read_at = :now WHERE id = :id AND recipient_id = :me AND read_at IS NULL")
				.param("now", DbTime.toDb(clock.instant())).param("id", notificationId).param("me", user.id()).update();
		return find(notificationId, user.id());
	}

	/** Đánh dấu đã đọc và kiểm tra lại quyền/sự tồn tại của đích. */
	@Transactional
	OpenNotificationResponse open(CurrentUser user, UUID notificationId) {
		NotificationItem read = markRead(user, notificationId);
		boolean available = targets.isAvailable(user.id(), read.groupId(), read.entityType(), read.entityId());
		if (available) {
			return new OpenNotificationResponse(read, true, read.target(), null, null);
		}
		boolean member = targets.isAvailable(user.id(), read.groupId(), "GROUP", read.groupId());
		ScreenTarget fallback = NotificationTargets.fallback(read.target().screen(), read.groupId(), member);
		return new OpenNotificationResponse(read, false, read.target(), NotificationTargets.UNAVAILABLE_MESSAGE, fallback);
	}

	@Transactional
	MarkAllReadResponse readAll(CurrentUser user, MarkAllReadRequest request) {
		String tab = parseTab(request == null ? null : request.tab());
		UUID groupId = request == null ? null : request.groupId();
		JdbcClient.StatementSpec spec = jdbc.sql("""
				UPDATE notifications n SET read_at = :now FROM notification_events e
				 WHERE e.id = n.event_id AND n.recipient_id = :me AND n.read_at IS NULL
				""" + (groupId != null ? " AND n.project_id = :group" : "") + tabClause(tab))
				.param("now", DbTime.toDb(clock.instant())).param("me", user.id());
		if (groupId != null) {
			spec = spec.param("group", groupId);
		}
		if (!tab.equals("ALL")) {
			spec = spec.param("actionTypes", NotificationType.actionRequiredNames());
		}
		return new MarkAllReadResponse(spec.update());
	}
}
