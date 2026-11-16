package app.hanare.schedule;

import app.hanare.common.DbTime;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.schedule.CycleRepository.CycleRow;
import app.hanare.security.GroupAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job kỳ checkpoint (spec 0.5, 12.9). {@link #runOnce(Instant)} là toàn bộ logic, test được bằng {@code FixedClock};
 * {@link #tick()} chỉ là lớp mỏng cho {@code @Scheduled} (bật bởi {@link SchedulingConfig}, tắt ở profile test).
 *
 * <p>Mỗi kỳ được xử lý trong transaction riêng, khóa dòng {@code FOR UPDATE SKIP LOCKED} (chạy đồng thời → bên đến sau
 * bỏ qua/không làm gì) và chống trùng bằng khóa {@code dedup_key} của thông báo. Thứ tự: đóng kỳ quá hạn → mở kỳ tới
 * giờ → nhắc → sinh bù kỳ tương lai. Chạy lặp với cùng/lớn hơn {@code now} không tạo bản ghi thứ hai.
 */
@Component
public class CheckpointJob {

	private static final Logger log = LoggerFactory.getLogger(CheckpointJob.class);

	/** Số kỳ đã đóng / mở, số thông báo nhắc mới gửi, số kỳ tương lai mới sinh trong một lần chạy. */
	public record RunResult(int closed, int opened, int reminders, int generatedCycles) {
	}

	private final JdbcClient jdbc;
	private final CycleRepository repo;
	private final ScheduleService schedules;
	private final NotificationService notifications;
	private final GroupAccess access;
	private final ObjectProvider<CutoffHook> hooks;
	private final Clock clock;
	private final TransactionTemplate tx;

	CheckpointJob(JdbcClient jdbc, CycleRepository repo, ScheduleService schedules, NotificationService notifications,
			GroupAccess access, ObjectProvider<CutoffHook> hooks, Clock clock, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;
		this.repo = repo;
		this.schedules = schedules;
		this.notifications = notifications;
		this.access = access;
		this.hooks = hooks;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	@Scheduled(fixedDelayString = "${hanare.jobs.checkpoint-delay:PT30S}", initialDelayString = "PT15S")
	void tick() {
		try {
			runOnce(clock.instant());
		}
		catch (RuntimeException ex) {
			log.error("CheckpointJob lỗi", ex);
		}
	}

	public RunResult runOnce(Instant now) {
		int closed = 0;
		for (UUID id : ids("""
				SELECT id FROM checkpoint_cycles WHERE status IN ('SCHEDULED', 'OPEN') AND closes_at <= :now ORDER BY closes_at
				""", now)) {
			closed += attempt("đóng kỳ " + id, () -> closeOne(id, now));
		}
		int opened = 0;
		for (UUID id : ids("""
				SELECT id FROM checkpoint_cycles WHERE status = 'SCHEDULED' AND opens_at <= :now AND closes_at > :now
				 ORDER BY opens_at
				""", now)) {
			opened += attempt("mở kỳ " + id, () -> openOne(id, now));
		}
		int reminders = 0;
		for (UUID id : ids("""
				SELECT c.id FROM checkpoint_cycles c JOIN projects p ON p.id = c.project_id AND p.archived_at IS NULL
				 WHERE c.status IN ('SCHEDULED', 'OPEN') AND c.reminder_at <= :now AND c.closes_at > :now
				   AND NOT EXISTS (SELECT 1 FROM notification_events e WHERE e.dedup_key = 'checkpoint:' || c.id::text || ':reminder')
				 ORDER BY c.reminder_at
				""", now)) {
			reminders += attempt("nhắc kỳ " + id, () -> remindOne(id, now));
		}
		int generated = 0;
		for (UUID projectId : ids("""
				SELECT p.id FROM projects p
				 WHERE p.archived_at IS NULL
				   AND EXISTS (SELECT 1 FROM checkpoint_schedules s WHERE s.project_id = p.id)
				   AND (SELECT count(*) FROM checkpoint_cycles c
				         WHERE c.project_id = p.id AND c.status <> 'CANCELLED' AND c.closes_at > :now) < :ahead
				""", now, ScheduleService.CYCLES_AHEAD)) {
			generated += attempt("sinh kỳ nhóm " + projectId, () -> topUpOne(projectId, now));
		}
		return new RunResult(closed, opened, reminders, generated);
	}

	private List<UUID> ids(String sql, Instant now) {
		return jdbc.sql(sql).param("now", DbTime.toDb(now)).query(UUID.class).list();
	}

	private List<UUID> ids(String sql, Instant now, int ahead) {
		return jdbc.sql(sql).param("now", DbTime.toDb(now)).param("ahead", ahead).query(UUID.class).list();
	}

	/** Chạy trong transaction riêng; lỗi một kỳ không chặn các kỳ khác (kỳ đó được thử lại ở lần chạy sau). */
	private int attempt(String what, IntSupplier work) {
		try {
			Integer result = tx.execute(status -> work.getAsInt());
			return result == null ? 0 : result;
		}
		catch (RuntimeException ex) {
			log.error("CheckpointJob: lỗi khi {}", what, ex);
			return 0;
		}
	}

	/** Khóa dòng kỳ nếu còn thỏa điều kiện; rỗng nếu đã đổi trạng thái hoặc đang bị transaction khác giữ. */
	private Optional<UUID> lock(UUID cycleId, String condition, Instant now) {
		return jdbc.sql("SELECT project_id FROM checkpoint_cycles WHERE id = :id AND " + condition
				+ " FOR UPDATE SKIP LOCKED")
				.param("id", cycleId).param("now", DbTime.toDb(now)).query(UUID.class).optional();
	}

	private int closeOne(UUID cycleId, Instant now) {
		Optional<UUID> projectId = lock(cycleId, "status IN ('SCHEDULED', 'OPEN') AND closes_at <= :now", now);
		if (projectId.isEmpty()) {
			return 0;
		}
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'CLOSED', closed_at = :now, version = version + 1 WHERE id = :id")
				.param("now", DbTime.toDb(now)).param("id", cycleId).update();
		CycleService.CycleRef ref = repo.find(projectId.get(), cycleId).orElseThrow().ref();
		// Mọi hook chạy trong CÙNG transaction: một hook lỗi → kỳ vẫn chưa đóng, thử lại ở lần chạy sau.
		hooks.orderedStream().forEach(h -> h.onCycleClosed(ref));
		return 1;
	}

	private int openOne(UUID cycleId, Instant now) {
		if (lock(cycleId, "status = 'SCHEDULED' AND opens_at <= :now AND closes_at > :now", now).isEmpty()) {
			return 0;
		}
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'OPEN', version = version + 1 WHERE id = :id")
				.param("id", cycleId).update();
		return 1;
	}

	private int remindOne(UUID cycleId, Instant now) {
		Optional<UUID> projectId = lock(cycleId,
				"status IN ('SCHEDULED', 'OPEN') AND reminder_at <= :now AND closes_at > :now", now);
		if (projectId.isEmpty()) {
			return 0;
		}
		CycleRow cycle = repo.find(projectId.get(), cycleId).orElseThrow();
		ZoneId zone = ZoneId.of(cycle.timezone());
		long minutes = Duration.between(cycle.reminderAt(), cycle.closesAt()).toMinutes();
		boolean created = notifications.publish(new NotificationEvent(projectId.get(), "CHECKPOINT_REMINDER",
				"CHECKPOINT_CYCLE", cycleId, cycleId, "checkpoint:" + cycleId + ":reminder",
				access.activeMemberIds(projectId.get()), "Đến lúc cập nhật ngắn về công việc rồi!",
				"Kỳ " + cycle.sequenceNo() + " chốt lúc " + ScheduleLabels.cutoffMoment(cycle.closesAt(), zone) + " (còn "
						+ ScheduleLabels.durationVi(minutes) + ").",
				Map.of("cycleId", cycleId.toString(), "subjectLabel", "Kỳ " + cycle.sequenceNo())));
		return created ? 1 : 0;
	}

	private int topUpOne(UUID projectId, Instant now) {
		Optional<UUID> locked = jdbc.sql("SELECT id FROM projects WHERE id = :p FOR UPDATE SKIP LOCKED")
				.param("p", projectId).query(UUID.class).optional();
		return locked.isEmpty() ? 0 : schedules.topUp(projectId, now);
	}
}
