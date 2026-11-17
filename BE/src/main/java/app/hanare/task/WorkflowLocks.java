package app.hanare.task;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Serialize workflow mutations within a small student group, before taking cycle/task/work locks.
 * Call only inside a transaction. Reads stay concurrent; different groups never share this lock. */
public final class WorkflowLocks {

	private WorkflowLocks() { }

	public static void project(JdbcClient jdbc, UUID projectId) {
		jdbc.sql("SELECT id FROM projects WHERE id = :p FOR UPDATE").param("p", projectId)
				.query(UUID.class).optional().orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy nhóm."));
	}

	public static void round(JdbcClient jdbc, UUID roundId) {
		UUID projectId = jdbc.sql("SELECT project_id FROM task_review_rounds WHERE id = :r").param("r", roundId)
				.query(UUID.class).optional().orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy vòng xử lý."));
		project(jdbc, projectId);
		jdbc.sql("SELECT c.id FROM checkpoint_cycles c JOIN task_review_rounds r ON r.cycle_id = c.id WHERE r.id = :r FOR UPDATE OF c")
				.param("r", roundId).query(UUID.class).single();
		jdbc.sql("SELECT id FROM task_review_rounds WHERE id = :r FOR UPDATE").param("r", roundId).query(UUID.class).single();
	}
}
