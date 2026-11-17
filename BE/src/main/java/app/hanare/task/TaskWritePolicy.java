package app.hanare.task;

import app.hanare.common.ApiException;
import app.hanare.task.TaskReader.Ctx;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Shared state rules for API guards and allowedActions. */
public final class TaskWritePolicy {
	private TaskWritePolicy() { }

	public static boolean reported(JdbcClient jdbc, UUID roundId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM checkpoint_report_items i JOIN checkpoint_reports r ON r.id = i.report_id
				 WHERE i.round_id = :r AND r.status = 'SUBMITTED')
				""").param("r", roundId).query(Boolean.class).single();
	}

	public static boolean canExecute(Scope s, Ctx c, boolean reported) {
		return c.committed() && "ACTIVE".equals(c.round().state()) && c.sub() == null
				&& !reported && !s.cycle(c.round().cycleId()).isClosed(s.now());
	}

	public static void requireExecution(JdbcClient jdbc, Scope s, Ctx c) {
		if (!canExecute(s, c, reported(jdbc, c.round().id()))) {
			throw ApiException.invalidState("Vòng đã nộp kết quả, đã báo cáo hoặc đã khóa. Hãy cập nhật báo cáo nếu kỳ còn mở.");
		}
	}

	public static void requirePlanEdit(JdbcClient jdbc, Scope s, Ctx c) {
		if (s.cycle(c.round().cycleId()).isClosed(s.now()) || (c.committed()
				&& !canExecute(s, c, reported(jdbc, c.round().id())))) {
			throw ApiException.invalidState("Kế hoạch của vòng đã bị khóa.");
		}
	}

	public static void requireRoundExecution(JdbcClient jdbc, UUID roundId, java.time.Instant now) {
		boolean allowed = jdbc.sql("""
				SELECT EXISTS(SELECT 1 FROM task_review_rounds r JOIN checkpoint_cycles c ON c.id = r.cycle_id
				 WHERE r.id = :r AND r.state = 'ACTIVE' AND r.effective_plan_id IS NOT NULL
				 AND c.status IN ('OPEN','SCHEDULED') AND c.closes_at >= :now
				 AND NOT EXISTS(SELECT 1 FROM task_submissions s WHERE s.round_id = r.id))
				""").param("r", roundId).param("now", app.hanare.common.DbTime.toDb(now)).query(Boolean.class).single();
		if (!allowed || reported(jdbc, roundId)) throw ApiException.invalidState("Vòng đã nộp, đã báo cáo hoặc đã khóa.");
	}
}
