package app.hanare.task;

import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Bean giả cho test BE-B1: NotificationService ghi lại sự kiện (BE-A cài thật) + TaskAcceptedHook ghi lại (BE-B2 cài thật). */
@TestConfiguration(proxyBeanMethods = false)
public class B1TestConfig {

	/** Ghi lại mọi sự kiện, tôn trọng dedupKey như bản thật. */
	public static class RecordingNotifications implements NotificationService {
		public final List<NotificationEvent> events = new ArrayList<>();
		private final Set<String> keys = new HashSet<>();

		@Override
		public synchronized boolean publish(NotificationEvent event) {
			if (!keys.add(event.dedupKey())) {
				return false;
			}
			events.add(event);
			return true;
		}

		public synchronized List<NotificationEvent> of(String type) {
			return events.stream().filter(e -> e.eventType().equals(type)).toList();
		}

		public synchronized void clear() {
			events.clear();
			keys.clear();
		}
	}

	/** Hook giả của BE-B2: đếm lần gọi; nếu {@code writePoints} thì ghi settlement + AWARD thật (như B2) để test award. */
	public static class RecordingHook implements TaskAcceptedHook {
		public final List<AcceptedSubmission> calls = new ArrayList<>();
		public volatile boolean writePoints;
		public volatile boolean failNext;
		private final JdbcClient jdbc;

		RecordingHook(JdbcClient jdbc) {
			this.jdbc = jdbc;
		}

		@Override
		public synchronized void onSubmissionAccepted(AcceptedSubmission a) {
			calls.add(a);
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("hook failure");
			}
			if (writePoints) {
				UUID budget = jdbc.sql("SELECT id FROM task_point_budgets WHERE task_id = :t").param("t", a.taskId()).query(UUID.class).single();
				UUID assignment = jdbc.sql("SELECT id FROM task_point_assignments WHERE budget_id = :b AND status = 'CURRENT'")
						.param("b", budget).query(UUID.class).single();
				UUID recipient = jdbc.sql("SELECT recipient_id FROM task_point_assignments WHERE id = :a").param("a", assignment).query(UUID.class).single();
				long units = jdbc.sql("SELECT point_units FROM task_point_budgets WHERE id = :b").param("b", budget).query(Long.class).single();
				UUID settlement = jdbc.sql("""
						INSERT INTO task_point_settlements (project_id, budget_id, assignment_id, recipient_id, accepted_submission_id, approved_by)
						VALUES (:p, :b, :a, :r, :s, :by) RETURNING id
						""").param("p", a.projectId()).param("b", budget).param("a", assignment).param("r", recipient)
						.param("s", a.submissionId()).param("by", a.reviewerId()).query(UUID.class).single();
				jdbc.sql("""
						INSERT INTO point_ledger_entries (project_id, budget_id, settlement_id, recipient_id, delta_units, entry_type, idempotency_key)
						VALUES (:p, :b, :s, :r, :u, 'AWARD', :k)
						""").param("p", a.projectId()).param("b", budget).param("s", settlement).param("r", recipient).param("u", units)
						.param("k", "award:" + a.submissionId()).update();
			}
		}

		public synchronized void reset() {
			calls.clear();
			writePoints = false;
			failNext = false;
		}
	}

	@Bean
	@Primary
	RecordingNotifications recordingNotifications() {
		return new RecordingNotifications();
	}

	@Bean
	RecordingHook recordingHook(JdbcClient jdbc) {
		return new RecordingHook(jdbc);
	}
}
