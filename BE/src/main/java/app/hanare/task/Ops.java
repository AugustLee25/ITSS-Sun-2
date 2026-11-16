package app.hanare.task;

import app.hanare.common.DbTime;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Thao tác dùng chung của module task: khóa, tăng version, audit/lịch sử, thông báo. */
@Component
public class Ops {

	private final JdbcClient jdbc;
	private final Clock clock;
	private final JsonMapper mapper;
	private final NotificationService notifications;

	public Ops(JdbcClient jdbc, Clock clock, JsonMapper mapper, NotificationService notifications) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.mapper = mapper;
		this.notifications = notifications;
	}

	public Instant now() {
		return J.now(clock);
	}

	/** Khóa dòng vòng xử lý (tuần tự hóa duyệt/nộp/chuyển kỳ của cùng task). */
	public void lockRound(UUID roundId) {
		jdbc.sql("SELECT id FROM task_review_rounds WHERE id = :r FOR UPDATE").param("r", roundId).query(UUID.class).list();
	}

	public void bumpTask(UUID taskId) {
		jdbc.sql("UPDATE tasks SET version = version + 1 WHERE id = :t").param("t", taskId).update();
	}

	public void audit(UUID projectId, UUID actorId, String entityType, UUID entityId, String action, Object before, Object after) {
		jdbc.sql("""
				INSERT INTO audit_logs (project_id, actor_id, entity_type, entity_id, action, before_data, after_data, occurred_at)
				VALUES (:p, :a, :et, :eid, :act, CAST(:b AS jsonb), CAST(:af AS jsonb), :at)
				""")
				.param("p", projectId).param("a", actorId).param("et", entityType).param("eid", entityId).param("act", action)
				.param("b", before == null ? null : mapper.writeValueAsString(before))
				.param("af", after == null ? null : mapper.writeValueAsString(after)).param("at", DbTime.toDb(now())).update();
	}

	/** Dòng lịch sử task = audit_logs(entity_type='TASK', action = TaskHistoryKind). */
	public void history(UUID projectId, UUID actorId, UUID taskId, String kind, int roundNo, String summary, UUID refId) {
		audit(projectId, actorId, "TASK", taskId, kind, null, J.o("roundNo", roundNo, "summary", summary, "refId", refId));
	}

	public void notify(UUID projectId, String type, String entityType, UUID entityId, UUID cycleId, String dedupKey,
			Collection<UUID> recipients, UUID actorToExclude, String title, String message, Map<String, Object> payload) {
		Set<UUID> to = new LinkedHashSet<>(recipients);
		if (actorToExclude != null) {
			to.remove(actorToExclude);
		}
		notifications.publish(new NotificationEvent(projectId, type, entityType, entityId, cycleId, dedupKey, to, title, message,
				payload));
	}
}
