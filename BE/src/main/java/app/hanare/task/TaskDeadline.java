package app.hanare.task;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Date-only project deadline interpreted in the group's timezone. */
public record TaskDeadline(JdbcClient jdbc) {
	public Instant groupLimit(UUID projectId) {
		return jdbc.sql("SELECT deadline, timezone FROM projects WHERE id=:p").param("p", projectId).query((rs, i) -> {
			ZoneId zone = ZoneId.of(rs.getString("timezone"));
			return J.inst(rs, "deadline").atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
		}).single();
	}
}
