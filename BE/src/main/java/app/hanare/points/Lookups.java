package app.hanare.points;

import app.hanare.common.DbTime;
import app.hanare.points.Refs.CycleRef;
import app.hanare.points.Refs.TaskRef;
import app.hanare.points.Refs.UserRef;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Tra cứu nhanh tên người/tiêu đề task/kỳ để dựng DTO (đọc SQL, không gọi service module khác). */
@Component
public class Lookups {

	private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");

	/** Tiêu đề task = bản kế hoạch mới nhất (vòng mới nhất, revision mới nhất) có tiêu đề. */
	public static final String TASK_TITLE_SQL = """
			SELECT COALESCE((SELECT p.title FROM task_plan_revisions p JOIN task_review_rounds r ON r.id = p.round_id
			                  WHERE r.task_id = :taskId AND p.title IS NOT NULL
			                  ORDER BY r.round_no DESC, p.revision_no DESC LIMIT 1), 'Task chưa đặt tên')
			""";

	private final JdbcClient jdbc;

	public Lookups(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public UserRef user(UUID userId) {
		if (userId == null) {
			return null;
		}
		return jdbc.sql("SELECT id, name FROM users WHERE id = :id").param("id", userId)
				.query((rs, i) -> new UserRef(rs.getObject("id", UUID.class), rs.getString("name"))).optional()
				.orElse(new UserRef(userId, "Thành viên"));
	}

	public Map<UUID, UserRef> users(Collection<UUID> ids) {
		Map<UUID, UserRef> map = new HashMap<>();
		if (ids == null || ids.isEmpty()) {
			return map;
		}
		jdbc.sql("SELECT id, name FROM users WHERE id IN (:ids)").param("ids", ids.stream().distinct().toList())
				.query((rs, i) -> new UserRef(rs.getObject("id", UUID.class), rs.getString("name"))).list()
				.forEach(u -> map.put(u.userId(), u));
		return map;
	}

	public String taskTitle(UUID taskId) {
		return jdbc.sql(TASK_TITLE_SQL).param("taskId", taskId).query(String.class).single();
	}

	public TaskRef taskRef(UUID taskId) {
		return new TaskRef(taskId, taskTitle(taskId));
	}

	public String userName(UUID userId) {
		return user(userId).name();
	}

	public ZoneId projectZone(UUID projectId) {
		String tz = jdbc.sql("SELECT timezone FROM projects WHERE id = :p").param("p", projectId).query(String.class)
				.optional().orElse("Asia/Ho_Chi_Minh");
		return ZoneId.of(tz);
	}

	public CycleRef cycle(UUID cycleId) {
		return jdbc.sql("""
				SELECT c.id, c.sequence_no, c.period_start_at, c.closes_at, p.timezone
				  FROM checkpoint_cycles c JOIN projects p ON p.id = c.project_id WHERE c.id = :id
				""").param("id", cycleId).query((rs, i) -> cycleOf(rs.getObject("id", UUID.class), rs.getInt("sequence_no"),
						DbTime.instant(rs, "period_start_at"), DbTime.instant(rs, "closes_at"), rs.getString("timezone")))
				.optional().orElse(null);
	}

	public static CycleRef cycleOf(UUID id, int sequenceNo, Instant start, Instant closes, String timezone) {
		ZoneId zone = ZoneId.of(timezone);
		String label = "Kỳ " + sequenceNo + " · " + LABEL_TIME.format(start.atZone(zone)) + " → "
				+ LABEL_TIME.format(closes.atZone(zone));
		return new CycleRef(id, sequenceNo, start, closes, label);
	}

	public List<UUID> activeMembers(UUID projectId) {
		return jdbc.sql("SELECT user_id FROM project_members WHERE project_id = :p AND left_at IS NULL ORDER BY joined_at, user_id")
				.param("p", projectId).query(UUID.class).list();
	}
}
