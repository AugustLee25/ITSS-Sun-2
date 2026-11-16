package app.hanare.points;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Ghi {@code audit_logs} (append-only). Gọi trong cùng transaction với thay đổi nghiệp vụ. */
@Component
public class AuditWriter {

	private final JdbcClient jdbc;
	private final JsonMapper json;

	public AuditWriter(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public void log(UUID projectId, UUID actorId, String entityType, UUID entityId, String action,
			Map<String, Object> before, Map<String, Object> after) {
		jdbc.sql("""
				INSERT INTO audit_logs (project_id, actor_id, entity_type, entity_id, action, before_data, after_data)
				VALUES (:p, :a, :et, :eid, :act, CAST(:b AS jsonb), CAST(:af AS jsonb))
				""")
				.param("p", projectId).param("a", actorId).param("et", entityType).param("eid", entityId)
				.param("act", action).param("b", before == null ? null : json.writeValueAsString(before))
				.param("af", after == null ? null : json.writeValueAsString(after)).update();
	}
}
