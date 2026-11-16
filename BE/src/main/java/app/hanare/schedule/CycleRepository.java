package app.hanare.schedule;

import app.hanare.common.DbTime;
import app.hanare.schedule.ScheduleCalculator.CyclePlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Truy cập {@code checkpoint_schedules}/{@code checkpoint_cycles} (SQL tường minh). */
@Repository
class CycleRepository {

	/** Một dòng kỳ + múi giờ của lịch đã sinh ra kỳ đó (kỳ đã mở giữ múi giờ cũ). */
	record CycleRow(UUID id, UUID projectId, UUID scheduleId, int sequenceNo, UUID previousCycleId, Instant periodStartAt,
			Instant opensAt, Instant reminderAt, Instant closesAt, String status, Instant closedAt, Instant finalizedAt,
			String timezone) {

		CycleService.CycleRef ref() {
			return new CycleService.CycleRef(id, projectId, sequenceNo, previousCycleId, periodStartAt, opensAt, reminderAt,
					closesAt, status, closedAt, finalizedAt);
		}
	}

	record ScheduleRow(UUID id, UUID projectId, int versionNo, int weekday, LocalTime cutoff, String timezone,
			int reminderBeforeMinutes, Integer windowMinutes, Instant effectiveFrom, Instant agreedAt, UUID recordedBy,
			String recordedByName) {

		ScheduleSpec spec() {
			return new ScheduleSpec(weekday, cutoff, ZoneId.of(timezone), windowMinutes, reminderBeforeMinutes);
		}
	}

	private static final String CYCLE_SELECT = """
			SELECT c.id, c.project_id, c.schedule_id, c.sequence_no, c.previous_cycle_id, c.period_start_at, c.opens_at,
			       c.reminder_at, c.closes_at, c.status, c.closed_at, c.finalized_at, s.timezone
			  FROM checkpoint_cycles c JOIN checkpoint_schedules s ON s.id = c.schedule_id
			""";

	private static final String SCHEDULE_SELECT = """
			SELECT s.id, s.project_id, s.version_no, s.weekday, s.cutoff_local_time, s.timezone, s.reminder_before_minutes,
			       s.reporting_window_minutes, s.effective_from, s.agreed_at, s.recorded_by, u.name AS recorded_by_name
			  FROM checkpoint_schedules s JOIN users u ON u.id = s.recorded_by
			""";

	private final JdbcClient jdbc;

	CycleRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static CycleRow cycle(ResultSet rs, int i) throws SQLException {
		return new CycleRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("schedule_id", UUID.class), rs.getInt("sequence_no"),
				rs.getObject("previous_cycle_id", UUID.class), DbTime.instant(rs, "period_start_at"),
				DbTime.instant(rs, "opens_at"), DbTime.instant(rs, "reminder_at"), DbTime.instant(rs, "closes_at"),
				rs.getString("status"), DbTime.instant(rs, "closed_at"), DbTime.instant(rs, "finalized_at"),
				rs.getString("timezone"));
	}

	private static ScheduleRow schedule(ResultSet rs, int i) throws SQLException {
		return new ScheduleRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getInt("version_no"), rs.getInt("weekday"), rs.getObject("cutoff_local_time", LocalTime.class),
				rs.getString("timezone"), rs.getInt("reminder_before_minutes"),
				rs.getObject("reporting_window_minutes", Integer.class), DbTime.instant(rs, "effective_from"),
				DbTime.instant(rs, "agreed_at"), rs.getObject("recorded_by", UUID.class), rs.getString("recorded_by_name"));
	}

	// ------------------------------------------------------------------ cycles
	Optional<CycleRow> find(UUID projectId, UUID cycleId) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.id = :id")
				.param("p", projectId).param("id", cycleId).query(CycleRepository::cycle).optional();
	}

	Optional<CycleRow> findContaining(UUID projectId, Instant at) {
		return jdbc.sql(CYCLE_SELECT + """
				 WHERE c.project_id = :p AND c.status <> 'CANCELLED' AND c.period_start_at < :at AND :at <= c.closes_at
				""")
				.param("p", projectId).param("at", DbTime.toDb(at)).query(CycleRepository::cycle).optional();
	}

	Optional<CycleRow> findBySequence(UUID projectId, int sequenceNo) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.sequence_no = :n AND c.status <> 'CANCELLED'")
				.param("p", projectId).param("n", sequenceNo).query(CycleRepository::cycle).optional();
	}

	Optional<CycleRow> findNextOf(UUID projectId, UUID cycleId) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.previous_cycle_id = :id AND c.status <> 'CANCELLED'")
				.param("p", projectId).param("id", cycleId).query(CycleRepository::cycle).optional();
	}

	Optional<CycleRow> first(UUID projectId) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.status <> 'CANCELLED' ORDER BY c.sequence_no LIMIT 1")
				.param("p", projectId).query(CycleRepository::cycle).optional();
	}

	Optional<CycleRow> last(UUID projectId) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.status <> 'CANCELLED' ORDER BY c.sequence_no DESC LIMIT 1")
				.param("p", projectId).query(CycleRepository::cycle).optional();
	}

	List<CycleRow> list(UUID projectId, String status, boolean desc, long offset, int limit) {
		JdbcClient.StatementSpec spec = jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND " + statusFilter(status)
				+ " ORDER BY c.sequence_no " + (desc ? "DESC" : "ASC") + " LIMIT :limit OFFSET :offset")
				.param("p", projectId).param("limit", limit).param("offset", offset);
		if (status != null) {
			spec = spec.param("status", status);
		}
		return spec.query(CycleRepository::cycle).list();
	}

	long count(UUID projectId, String status) {
		JdbcClient.StatementSpec spec = jdbc
				.sql("SELECT count(*) FROM checkpoint_cycles c WHERE c.project_id = :p AND " + statusFilter(status))
				.param("p", projectId);
		if (status != null) {
			spec = spec.param("status", status);
		}
		return spec.query(Long.class).single();
	}

	private static String statusFilter(String status) {
		return status == null ? "c.status <> 'CANCELLED'" : "c.status = :status";
	}

	List<CycleRow> bySchedule(UUID projectId, UUID scheduleId) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.schedule_id = :s AND c.status <> 'CANCELLED' ORDER BY c.sequence_no")
				.param("p", projectId).param("s", scheduleId).query(CycleRepository::cycle).list();
	}

	List<CycleRow> afterSequence(UUID projectId, int sequenceNo) {
		return jdbc.sql(CYCLE_SELECT + " WHERE c.project_id = :p AND c.sequence_no > :n AND c.status <> 'CANCELLED' ORDER BY c.sequence_no")
				.param("p", projectId).param("n", sequenceNo).query(CycleRepository::cycle).list();
	}

	int maxSequenceNo(UUID projectId) {
		return jdbc.sql("SELECT COALESCE(max(sequence_no), 0) FROM checkpoint_cycles WHERE project_id = :p")
				.param("p", projectId).query(Integer.class).single();
	}

	int countFuture(UUID projectId, Instant now) {
		return jdbc.sql("""
				SELECT count(*) FROM checkpoint_cycles
				 WHERE project_id = :p AND status <> 'CANCELLED' AND closes_at > :now
				""")
				.param("p", projectId).param("now", DbTime.toDb(now)).query(Integer.class).single();
	}

	/**
	 * Số thứ tự lớn nhất của kỳ phải GIỮ NGUYÊN khi đổi lịch: đã mở/đã nhắc/đã đóng, hoặc đã có task/kế hoạch/báo cáo gắn
	 * vào (đổi ranh giới sẽ làm lệch deadline đã lưu). Các kỳ sau đó mới được sinh lại.
	 */
	Optional<Integer> frozenMaxSequence(UUID projectId, Instant now) {
		return jdbc.sql("""
				SELECT max(c.sequence_no) FROM checkpoint_cycles c
				 WHERE c.project_id = :p AND c.status <> 'CANCELLED'
				   AND (c.status <> 'SCHEDULED' OR c.opens_at <= :now OR c.reminder_at <= :now
				        OR EXISTS (SELECT 1 FROM task_review_rounds r WHERE r.cycle_id = c.id)
				        OR EXISTS (SELECT 1 FROM task_plan_revisions v WHERE v.proposed_cycle_id = c.id)
				        OR EXISTS (SELECT 1 FROM checkpoint_participants cp WHERE cp.cycle_id = c.id))
				""")
				.param("p", projectId).param("now", DbTime.toDb(now)).query(Integer.class).optional();
	}

	UUID insertCycle(UUID projectId, UUID scheduleId, int sequenceNo, UUID previousCycleId, CyclePlan plan, Instant now) {
		String status = plan.opensAt().compareTo(now) <= 0 && plan.closesAt().isAfter(now) ? "OPEN" : "SCHEDULED";
		return jdbc.sql("""
				INSERT INTO checkpoint_cycles (project_id, schedule_id, sequence_no, previous_cycle_id, period_start_at,
				    opens_at, reminder_at, closes_at, status, created_at)
				VALUES (:p, :s, :n, :prev, :start, :opens, :rem, :close, :st, :now) RETURNING id
				""")
				.param("p", projectId).param("s", scheduleId).param("n", sequenceNo).param("prev", previousCycleId)
				.param("start", DbTime.toDb(plan.periodStartAt())).param("opens", DbTime.toDb(plan.opensAt()))
				.param("rem", DbTime.toDb(plan.reminderAt())).param("close", DbTime.toDb(plan.closesAt()))
				.param("st", status).param("now", DbTime.toDb(now)).query(UUID.class).single();
	}

	/**
	 * Sinh lại ranh giới của các kỳ chưa mở theo lịch mới bằng MỘT câu UPDATE (ràng buộc nối chuỗi kỳ được kiểm tra
	 * cuối câu lệnh). {@code ids.get(i)} nhận {@code plans.get(i)}.
	 */
	void regenerate(UUID scheduleId, List<UUID> ids, List<CyclePlan> plans) {
		if (ids.isEmpty()) {
			return;
		}
		StringBuilder values = new StringBuilder();
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) {
				values.append(", ");
			}
			values.append("(CAST(:id").append(i).append(" AS uuid), CAST(:ps").append(i).append(" AS timestamptz), CAST(:op")
					.append(i).append(" AS timestamptz), CAST(:rm").append(i).append(" AS timestamptz), CAST(:cl").append(i)
					.append(" AS timestamptz))");
		}
		String sql = "UPDATE checkpoint_cycles c SET schedule_id = :sid, period_start_at = v.ps, opens_at = v.op, "
				+ "reminder_at = v.rm, closes_at = v.cl, version = c.version + 1 FROM (VALUES " + values
				+ ") AS v(id, ps, op, rm, cl) WHERE c.id = v.id AND c.status = 'SCHEDULED'";
		JdbcClient.StatementSpec spec = jdbc.sql(sql).param("sid", scheduleId);
		for (int i = 0; i < ids.size(); i++) {
			CyclePlan p = plans.get(i);
			spec = spec.param("id" + i, ids.get(i)).param("ps" + i, DbTime.toDb(p.periodStartAt()))
					.param("op" + i, DbTime.toDb(p.opensAt())).param("rm" + i, DbTime.toDb(p.reminderAt()))
					.param("cl" + i, DbTime.toDb(p.closesAt()));
		}
		int updated = spec.update();
		if (updated != ids.size()) {
			throw new IllegalStateException("Sinh lại kỳ chỉ cập nhật " + updated + "/" + ids.size() + " kỳ");
		}
	}

	// ------------------------------------------------------------------ schedules
	UUID insertSchedule(UUID projectId, int versionNo, ScheduleSpec spec, Instant effectiveFrom, Instant agreedAt,
			UUID recordedBy) {
		return jdbc.sql("""
				INSERT INTO checkpoint_schedules (project_id, version_no, weekday, cutoff_local_time, timezone,
				    reminder_before_minutes, reporting_window_minutes, effective_from, agreed_at, recorded_by, source, created_at)
				VALUES (:p, :v, :wd, :ct, :tz, :rem, :win, :ef, :ag, :by, 'APP', :now) RETURNING id
				""")
				.param("p", projectId).param("v", versionNo).param("wd", spec.weekday()).param("ct", spec.cutoff())
				.param("tz", spec.zone().getId()).param("rem", spec.reminderMinutes()).param("win", spec.windowMinutes())
				.param("ef", DbTime.toDb(effectiveFrom)).param("ag", DbTime.toDb(agreedAt)).param("by", recordedBy)
				.param("now", DbTime.toDb(agreedAt)).query(UUID.class).single();
	}

	Optional<ScheduleRow> findSchedule(UUID projectId, UUID scheduleId) {
		return jdbc.sql(SCHEDULE_SELECT + " WHERE s.project_id = :p AND s.id = :id")
				.param("p", projectId).param("id", scheduleId).query(CycleRepository::schedule).optional();
	}

	Optional<ScheduleRow> latestSchedule(UUID projectId) {
		return jdbc.sql(SCHEDULE_SELECT + " WHERE s.project_id = :p ORDER BY s.version_no DESC LIMIT 1")
				.param("p", projectId).query(CycleRepository::schedule).optional();
	}

	List<ScheduleRow> schedules(UUID projectId) {
		return jdbc.sql(SCHEDULE_SELECT + " WHERE s.project_id = :p ORDER BY s.version_no DESC")
				.param("p", projectId).query(CycleRepository::schedule).list();
	}
}
