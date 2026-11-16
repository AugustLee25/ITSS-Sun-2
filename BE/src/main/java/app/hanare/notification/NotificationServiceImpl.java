package app.hanare.notification;

import app.hanare.common.DbTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cài {@link NotificationService} (CỔNG CHUNG). Ghi {@code notification_events} (một dòng/{@code dedupKey}) và
 * {@code notifications} (một dòng/người nhận) trong CÙNG transaction của người gọi. Idempotent: {@code dedupKey}
 * đã có (kể cả do giao dịch đồng thời) → không ghi gì và trả {@code false}.
 */
@Service
class NotificationServiceImpl implements NotificationService {

	private static final Map<String, Object> EMPTY = Map.of();

	private final JdbcClient jdbc;
	private final Clock clock;
	private final JsonMapper json;

	NotificationServiceImpl(JdbcClient jdbc, Clock clock, JsonMapper json) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.json = json;
	}

	@Override
	@Transactional
	public boolean publish(NotificationEvent event) {
		validate(event);
		Instant now = clock.instant();
		Optional<UUID> eventId = jdbc.sql("""
				INSERT INTO notification_events (project_id, event_type, entity_type, entity_id, cycle_id, dedup_key,
				    payload, occurred_at, created_at)
				VALUES (:project, :type, :entityType, :entityId, :cycleId, :dedup, CAST(:payload AS jsonb), :now, :now)
				ON CONFLICT (dedup_key) DO NOTHING RETURNING id
				""")
				.param("project", event.projectId()).param("type", event.eventType())
				.param("entityType", event.entityType()).param("entityId", event.entityId())
				.param("cycleId", event.cycleId()).param("dedup", event.dedupKey())
				.param("payload", json.writeValueAsString(event.payload() == null ? EMPTY : event.payload()))
				.param("now", DbTime.toDb(now)).query(UUID.class).optional();
		if (eventId.isEmpty()) {
			return false;
		}
		List<UUID> recipients = event.recipientIds() == null ? List.of()
				: new ArrayList<>(new LinkedHashSet<>(event.recipientIds()));
		if (!recipients.isEmpty()) {
			// Chỉ thành viên ĐANG hoạt động của nhóm mới nhận được thông báo.
			jdbc.sql("""
					INSERT INTO notifications (event_id, project_id, recipient_id, title, message, created_at)
					SELECT :event, :project, m.user_id, :title, :message, :now
					  FROM project_members m
					 WHERE m.project_id = :project AND m.left_at IS NULL AND m.user_id IN (:recipients)
					ON CONFLICT (event_id, recipient_id) DO NOTHING
					""")
					.param("event", eventId.get()).param("project", event.projectId()).param("title", event.title())
					.param("message", event.message()).param("now", DbTime.toDb(now)).param("recipients", recipients)
					.update();
		}
		return true;
	}

	private static void validate(NotificationEvent e) {
		if (e == null || e.projectId() == null || e.entityId() == null) {
			throw new IllegalArgumentException("NotificationEvent thiếu projectId/entityId");
		}
		if (!NotificationType.isKnown(e.eventType())) {
			throw new IllegalArgumentException("Loại thông báo không hợp lệ: " + e.eventType());
		}
		if (!NotificationEntityTypes.isKnown(e.entityType())) {
			throw new IllegalArgumentException("entityType không hợp lệ: " + e.entityType());
		}
		if (e.dedupKey() == null || e.dedupKey().isBlank()) {
			throw new IllegalArgumentException("NotificationEvent thiếu dedupKey");
		}
		if (e.title() == null || e.title().isBlank() || e.title().length() > 200 || e.message() == null
				|| e.message().isBlank()) {
			throw new IllegalArgumentException("NotificationEvent cần title (≤200 ký tự) và message");
		}
	}
}
