package app.hanare.task;

import app.hanare.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Durable replay under the project workflow lock; retrying a key with another body is rejected. */
@Component
public class WorkflowRequests {
	private final JdbcClient jdbc;
	private final JsonMapper mapper;
	public WorkflowRequests(JdbcClient jdbc, JsonMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
	private String hash(Object body) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	@SuppressWarnings("unchecked")
	public Map<String, Object> replay(UUID user, String operation, String key, Object body) {
		if (key == null || key.isBlank()) return null;
		if (key.length() > 200) throw ApiException.validation("Idempotency-Key", "Tối đa 200 ký tự.");
		return jdbc.sql("SELECT request_hash, response::text AS response FROM workflow_requests WHERE user_id = :u AND operation = :o AND request_key = :k")
				.param("u", user).param("o", operation).param("k", key).query((rs, i) -> {
					if (!hash(body).equals(rs.getString("request_hash"))) throw ApiException.of(HttpStatus.CONFLICT,
							"IDEMPOTENCY_KEY_REUSED", "Mã gửi đã được dùng với dữ liệu khác.");
					return (Map<String, Object>) mapper.readValue(rs.getString("response"), Map.class);
				}).optional().orElse(null);
	}
	public void save(UUID project, UUID user, String operation, String key, Object body, Object response) {
		if (key == null || key.isBlank()) return;
		jdbc.sql("INSERT INTO workflow_requests(project_id,user_id,operation,request_key,request_hash,response) VALUES(:p,:u,:o,:k,:h,CAST(:r AS jsonb))")
				.param("p", project).param("u", user).param("o", operation).param("k", key).param("h", hash(body))
				.param("r", mapper.writeValueAsString(response)).update();
	}
	public <T> T replayAs(UUID user, String operation, String key, Object body, Class<T> type) {
		Map<String, Object> result = replay(user, operation, key, body);
		return result == null ? null : mapper.convertValue(result, type);
	}
}
