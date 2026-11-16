package app.hanare.task;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Ngữ cảnh đọc của MỘT request trong một nhóm: múi giờ nhóm, "bây giờ", tên thành viên và các kỳ (một lần đọc)
 * để dựng {@code UserRef}/{@code CycleRef}/{@code CheckpointCycle} mà không truy vấn lặp.
 */
public final class Scope {

	/** Một dòng {@code checkpoint_cycles}. Kỳ = (periodStartAt, closesAt]. */
	public record CycleRow(UUID id, UUID projectId, UUID scheduleId, int sequenceNo, UUID previousCycleId,
			Instant periodStartAt, Instant opensAt, Instant reminderAt, Instant closesAt, String status,
			Instant closedAt, Instant finalizedAt) {

		public boolean contains(Instant at) {
			return at.isAfter(periodStartAt) && !at.isAfter(closesAt);
		}

		/** Đã qua giờ chốt hoặc đã bị đóng/chốt/hủy (kể cả khi job chốt chưa chạy kịp). */
		public boolean isClosed(Instant now) {
			return now.isAfter(closesAt) || !("SCHEDULED".equals(status) || "OPEN".equals(status));
		}

		public String phase(Instant now) {
			if ("FINALIZED".equals(status)) {
				return "FINALIZED";
			}
			return now.isAfter(closesAt) || "CLOSED".equals(status) ? "AWAITING_LEAD_REVIEW" : "TENTATIVE";
		}

		public static String phaseLabel(String phase) {
			return switch (phase) {
				case "FINALIZED" -> "Đã chốt đánh giá";
				case "AWAITING_LEAD_REVIEW" -> "Chờ TL kiểm tra";
				default -> "Tạm tính";
			};
		}

		public boolean reportWindowOpen(Instant now) {
			return "SCHEDULED".equals(status) || "OPEN".equals(status) ? !now.isBefore(opensAt) && !now.isAfter(closesAt) : false;
		}
	}

	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd/MM HH:mm");
	private static final String[] WEEKDAYS = { "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ nhật" };

	private final UUID projectId;
	private final ZoneId tz;
	private final Instant now;
	private final Map<UUID, String> names;
	private final Map<UUID, CycleRow> cycles;
	private final UUID leaderId;

	Scope(UUID projectId, ZoneId tz, Instant now, Map<UUID, String> names, Map<UUID, CycleRow> cycles, UUID leaderId) {
		this.leaderId = leaderId;
		this.projectId = projectId;
		this.tz = tz;
		this.now = now;
		this.names = names;
		this.cycles = cycles;
	}

	public UUID projectId() {
		return projectId;
	}

	public UUID leaderId() {
		return leaderId;
	}

	public ZoneId tz() {
		return tz;
	}

	public Instant now() {
		return now;
	}

	public String name(UUID userId) {
		return names.getOrDefault(userId, "Thành viên");
	}

	public Map<String, Object> user(UUID userId) {
		return userId == null ? null : J.o("userId", userId, "name", name(userId));
	}

	public CycleRow cycle(UUID id) {
		CycleRow c = cycles.get(id);
		if (c == null) {
			throw ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint.");
		}
		return c;
	}

	public Optional<CycleRow> cycleOpt(UUID id) {
		return Optional.ofNullable(cycles.get(id));
	}

	public List<CycleRow> allCycles() {
		return cycles.values().stream().sorted((a, b) -> Integer.compare(a.sequenceNo(), b.sequenceNo())).toList();
	}

	/** Kỳ chứa {@code at} (không kể kỳ CANCELLED). */
	public Optional<CycleRow> containing(Instant at) {
		return cycles.values().stream().filter(c -> !"CANCELLED".equals(c.status()) && c.contains(at)).findFirst();
	}

	public Optional<CycleRow> next(CycleRow c) {
		return cycles.values().stream().filter(x -> !"CANCELLED".equals(x.status()) && x.sequenceNo() == c.sequenceNo() + 1)
				.findFirst();
	}

	public String cycleLabel(CycleRow c) {
		return "Kỳ " + c.sequenceNo() + " · " + LABEL.format(c.periodStartAt().atZone(tz)) + " → "
				+ LABEL.format(c.closesAt().atZone(tz));
	}

	public Map<String, Object> cycleRef(UUID cycleId) {
		return cycleRef(cycle(cycleId));
	}

	public Map<String, Object> cycleRef(CycleRow c) {
		return J.o("id", c.id(), "sequenceNo", c.sequenceNo(), "periodStartAt", c.periodStartAt(), "closesAt", c.closesAt(),
				"label", cycleLabel(c));
	}

	/** {@code CheckpointCycle} của api-types. */
	public Map<String, Object> cycleDto(CycleRow c) {
		String phase = c.phase(now);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c.id());
		m.put("groupId", projectId);
		m.put("scheduleId", c.scheduleId());
		m.put("sequenceNo", c.sequenceNo());
		m.put("periodStartAt", J.t(c.periodStartAt()));
		m.put("opensAt", J.t(c.opensAt()));
		m.put("reminderAt", J.t(c.reminderAt()));
		m.put("closesAt", J.t(c.closesAt()));
		m.put("status", c.status());
		m.put("phase", phase);
		m.put("phaseLabel", CycleRow.phaseLabel(phase));
		m.put("finalizedAt", J.t(c.finalizedAt()));
		m.put("isCurrent", c.contains(now));
		m.put("reportWindowOpen", c.reportWindowOpen(now));
		m.put("timezone", tz.getId());
		m.put("label", cycleLabel(c));
		return m;
	}

	/** "20:00 Thứ Sáu 20/11" theo múi giờ nhóm. */
	public String instantLabel(Instant at) {
		if (at == null) {
			return null;
		}
		var z = at.atZone(tz);
		return String.format("%02d:%02d %s %02d/%02d", z.getHour(), z.getMinute(), WEEKDAYS[z.getDayOfWeek().getValue() - 1],
				z.getDayOfMonth(), z.getMonthValue());
	}

	// ------------------------------------------------------------------ factory
	@Component
	public static class Factory {

		private final JdbcClient jdbc;
		private final Clock clock;

		public Factory(JdbcClient jdbc, Clock clock) {
			this.jdbc = jdbc;
			this.clock = clock;
		}

		public Scope of(UUID projectId) {
			String tzId = jdbc.sql("SELECT timezone FROM projects WHERE id = :p").param("p", projectId).query(String.class)
					.optional().orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy nhóm."));
			Map<UUID, String> names = new HashMap<>();
			jdbc.sql("""
					SELECT u.id, u.name FROM users u JOIN project_members m ON m.user_id = u.id WHERE m.project_id = :p
					""").param("p", projectId).query((rs, i) -> {
				names.put(rs.getObject("id", UUID.class), rs.getString("name"));
				return 0;
			}).list();
			Map<UUID, CycleRow> cycles = new HashMap<>();
			jdbc.sql("""
					SELECT id, project_id, schedule_id, sequence_no, previous_cycle_id, period_start_at, opens_at, reminder_at,
					       closes_at, status, closed_at, finalized_at
					  FROM checkpoint_cycles WHERE project_id = :p
					""").param("p", projectId).query((rs, i) -> {
				CycleRow c = new CycleRow(J.uuid(rs, "id"), J.uuid(rs, "project_id"), J.uuid(rs, "schedule_id"),
						rs.getInt("sequence_no"), J.uuid(rs, "previous_cycle_id"), J.inst(rs, "period_start_at"),
						J.inst(rs, "opens_at"), J.inst(rs, "reminder_at"), J.inst(rs, "closes_at"), rs.getString("status"),
						J.inst(rs, "closed_at"), J.inst(rs, "finalized_at"));
				cycles.put(c.id(), c);
				return 0;
			}).list();
			UUID leader = jdbc.sql("SELECT user_id FROM project_members WHERE project_id = :p AND role = 'LEAD' AND left_at IS NULL")
					.param("p", projectId).query(UUID.class).optional().orElse(null);
			return new Scope(projectId, ZoneId.of(tzId), J.now(clock), names, cycles, leader);
		}
	}
}
