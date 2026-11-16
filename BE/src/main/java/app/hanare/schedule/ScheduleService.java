package app.hanare.schedule;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.group.ItemsResponse;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.schedule.CycleRepository.CycleRow;
import app.hanare.schedule.CycleRepository.ScheduleRow;
import app.hanare.schedule.ScheduleCalculator.CyclePlan;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDto;
import app.hanare.schedule.ScheduleDtos.CheckpointScheduleDto;
import app.hanare.schedule.ScheduleDtos.SaveScheduleRequest;
import app.hanare.schedule.ScheduleDtos.SaveScheduleResponse;
import app.hanare.schedule.ScheduleDtos.SchedulePreviewCycle;
import app.hanare.schedule.ScheduleDtos.SchedulePreviewRequest;
import app.hanare.schedule.ScheduleDtos.SchedulePreviewResponse;
import app.hanare.schedule.ScheduleDtos.ScheduleWarning;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lịch checkpoint: kiểm tra, xem trước, lưu phiên bản mới, sinh kỳ (luôn có sẵn kỳ tương lai). */
@Service
public class ScheduleService {

	/** Kỳ hiện tại + 4 kỳ tương lai (hợp đồng §4.5: luôn sinh sẵn ≥ 4 kỳ tương lai). */
	static final int CYCLES_AHEAD = 5;
	private static final int PREVIEW_CYCLES = 4;
	private static final int MAX_GENERATED_PER_CALL = 300;
	private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

	private final CycleRepository repo;
	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final NotificationService notifications;
	private final Clock clock;

	ScheduleService(CycleRepository repo, JdbcClient jdbc, GroupAccess access, NotificationService notifications,
			Clock clock) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.access = access;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** Kết quả tạo lịch đầu của nhóm mới. */
	public record InitialSchedule(CheckpointScheduleDto schedule, CheckpointCycleDto currentCycle) {
	}

	// ------------------------------------------------------------------ kiểm tra đầu vào
	/** Kiểm tra lịch; sai quy tắc → 422 SCHEDULE_INVALID (thiếu trường đã bị bean validation chặn → 400). */
	public ScheduleSpec parseSpec(Integer weekday, String cutoffTime, String timezone, Integer windowMinutes,
			String fieldPrefix) {
		List<ApiError.FieldError> errors = new ArrayList<>();
		if (weekday == null || weekday < 1 || weekday > 7) {
			errors.add(new ApiError.FieldError(fieldPrefix + "weekday", "Ngày chốt phải từ 1 (Thứ Hai) đến 7 (Chủ nhật)."));
		}
		LocalTime cutoff = null;
		if (cutoffTime == null || !HH_MM.matcher(cutoffTime.trim()).matches()) {
			errors.add(new ApiError.FieldError(fieldPrefix + "cutoffTime", "Giờ chốt phải có dạng HH:mm (00:00–23:59)."));
		}
		else {
			cutoff = LocalTime.parse(cutoffTime.trim());
		}
		ZoneId zone = null;
		if (timezone == null || !isValidZone(timezone.trim())) {
			errors.add(new ApiError.FieldError(fieldPrefix + "timezone", "Múi giờ không hợp lệ (vd. Asia/Ho_Chi_Minh)."));
		}
		else {
			zone = ZoneId.of(timezone.trim());
		}
		if (windowMinutes != null && (windowMinutes < 60 || windowMinutes > 20160)) {
			errors.add(new ApiError.FieldError(fieldPrefix + "reportingWindowMinutes",
					"Khung mở báo cáo phải từ 60 đến 20160 phút (hoặc để trống = mở cả kỳ)."));
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEDULE_INVALID", "Lịch checkpoint không hợp lệ.",
					errors);
		}
		return new ScheduleSpec(weekday, cutoff, zone, windowMinutes, ScheduleSpec.DEFAULT_REMINDER_MINUTES);
	}

	private boolean isValidZone(String tz) {
		if (tz.isEmpty() || tz.length() > 64 || !ZoneId.getAvailableZoneIds().contains(tz)) {
			return false;
		}
		return Boolean.TRUE.equals(jdbc.sql("SELECT hanare_valid_timezone(:tz)").param("tz", tz).query(Boolean.class).single());
	}

	// ------------------------------------------------------------------ tạo lịch đầu (gọi từ group)
	/** Lịch v1 + kỳ đầu + 4 kỳ tương lai, trong transaction của người gọi. {@code startAt} = 00:00 ngày bắt đầu theo múi giờ nhóm. */
	@Transactional
	public InitialSchedule createInitial(UUID projectId, UUID leaderId, ScheduleSpec spec, Instant startAt, Instant now) {
		UUID scheduleId = repo.insertSchedule(projectId, 1, spec, startAt, now, leaderId);
		List<CyclePlan> plans = ScheduleCalculator.chain(spec, startAt, now, CYCLES_AHEAD);
		UUID previous = null;
		int seq = 0;
		List<UUID> ids = new ArrayList<>();
		for (CyclePlan plan : plans) {
			previous = repo.insertCycle(projectId, scheduleId, ++seq, previous, plan, now);
			ids.add(previous);
		}
		ScheduleRow schedule = repo.findSchedule(projectId, scheduleId).orElseThrow();
		CycleRow current = repo.findContaining(projectId, now).orElseGet(() -> repo.first(projectId).orElseThrow());
		return new InitialSchedule(CycleViews.toDto(schedule, true, now), CycleViews.toDto(current, now));
	}

	// ------------------------------------------------------------------ sinh kỳ
	/** Bổ sung kỳ để luôn có {@link #CYCLES_AHEAD} kỳ chưa kết thúc; trả về số kỳ đã sinh. Gọi khi đã giữ khóa dòng project. */
	@Transactional
	public int topUp(UUID projectId, Instant now) {
		int have = repo.countFuture(projectId, now);
		if (have >= CYCLES_AHEAD) {
			return 0;
		}
		Optional<ScheduleRow> schedule = repo.latestSchedule(projectId);
		Optional<CycleRow> last = repo.last(projectId);
		if (schedule.isEmpty() || last.isEmpty()) {
			return 0;
		}
		ScheduleSpec spec = schedule.get().spec();
		int needFuture = CYCLES_AHEAD - have;
		Instant start = last.get().closesAt();
		UUID previous = last.get().id();
		int seq = repo.maxSequenceNo(projectId);
		int futureAdded = 0;
		int generated = 0;
		while (futureAdded < needFuture && generated < MAX_GENERATED_PER_CALL) {
			Instant closes = ScheduleCalculator.firstCutoffAfter(spec, start);
			CyclePlan plan = ScheduleCalculator.plan(spec, start, closes);
			previous = repo.insertCycle(projectId, schedule.get().id(), ++seq, previous, plan, now);
			if (closes.isAfter(now)) {
				futureAdded++;
			}
			start = closes;
			generated++;
		}
		return generated;
	}

	/** Khóa dòng project để tuần tự hóa việc sinh/đổi lịch (job + API). */
	public void lockProject(UUID projectId) {
		jdbc.sql("SELECT id FROM projects WHERE id = :p FOR UPDATE").param("p", projectId).query(UUID.class).optional()
				.orElseThrow(() -> ApiException.of(ErrorCode.NOT_A_GROUP_MEMBER, null));
	}

	// ------------------------------------------------------------------ đổi lịch: neo + kỳ chưa mở
	private record Anchor(Instant start, int firstSequenceNo, List<CycleRow> unopened) {
	}

	private Anchor anchor(UUID projectId, Instant now) {
		Optional<Integer> frozen = repo.frozenMaxSequence(projectId, now);
		if (frozen.isPresent()) {
			CycleRow keep = repo.findBySequence(projectId, frozen.get()).orElseThrow();
			return new Anchor(keep.closesAt(), keep.sequenceNo() + 1, repo.afterSequence(projectId, keep.sequenceNo()));
		}
		CycleRow first = repo.first(projectId)
				.orElseThrow(() -> ApiException.invalidState("Nhóm chưa có kỳ checkpoint nào."));
		return new Anchor(first.periodStartAt(), first.sequenceNo(), repo.afterSequence(projectId, first.sequenceNo() - 1));
	}

	// ------------------------------------------------------------------ xem trước
	@Transactional(readOnly = true)
	public SchedulePreviewResponse preview(CurrentUser user, SchedulePreviewRequest req) {
		ScheduleSpec spec = parseSpec(req.weekday(), req.cutoffTime(), req.timezone(), req.reportingWindowMinutes(), "");
		Instant now = clock.instant();
		List<CyclePlan> plans;
		int firstSeq;
		List<ScheduleWarning> warnings = new ArrayList<>();
		if (req.groupId() != null) {
			access.requireLeader(req.groupId(), user);
			Anchor anchor = anchor(req.groupId(), now);
			plans = ScheduleCalculator.chain(spec, anchor.start(), null, PREVIEW_CYCLES);
			firstSeq = anchor.firstSequenceNo();
			CyclePlan first = plans.get(0);
			if (!ScheduleCalculator.isRegular(spec, first)) {
				long hours = ScheduleCalculator.periodHours(first);
				String target = ScheduleLabels.weekdayName(spec.weekday()) + " "
						+ spec.cutoff().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
				if (hours > 168) {
					warnings.add(new ScheduleWarning("TRANSITION_CYCLE_LONG",
							"Kỳ " + firstSeq + " kéo dài " + hours + " giờ vì đổi sang " + target + "."));
				}
				else {
					warnings.add(new ScheduleWarning("TRANSITION_CYCLE_SHORT",
							"Kỳ " + firstSeq + " chỉ dài " + hours + " giờ vì đổi sang " + target + "."));
				}
			}
		}
		else {
			LocalDate startDate = req.startDate() != null ? req.startDate() : LocalDate.ofInstant(now, spec.zone());
			Instant startAt = startDate.atStartOfDay(spec.zone()).toInstant();
			plans = ScheduleCalculator.chain(spec, startAt, now, PREVIEW_CYCLES);
			firstSeq = 1;
		}
		List<SchedulePreviewCycle> cycles = new ArrayList<>();
		for (int i = 0; i < plans.size(); i++) {
			CyclePlan p = plans.get(i);
			cycles.add(new SchedulePreviewCycle(firstSeq + i, ScheduleLabels.ms(p.periodStartAt()),
					ScheduleLabels.ms(p.opensAt()), ScheduleLabels.ms(p.reminderAt()), ScheduleLabels.ms(p.closesAt()),
					ScheduleCalculator.periodHours(p), !ScheduleCalculator.isRegular(spec, p),
					ScheduleLabels.cycleLabel(firstSeq + i, p.periodStartAt(), p.closesAt(), spec.zone())));
		}
		return new SchedulePreviewResponse(ScheduleLabels.summary(spec, now), spec.reminderMinutes(), cycles, warnings);
	}

	// ------------------------------------------------------------------ danh sách / lưu
	@Transactional(readOnly = true)
	public ItemsResponse<CheckpointScheduleDto> list(UUID groupId, CurrentUser user) {
		access.requireMember(groupId, user);
		Instant now = clock.instant();
		List<ScheduleRow> rows = repo.schedules(groupId);
		List<CheckpointScheduleDto> items = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			items.add(CycleViews.toDto(rows.get(i), i == 0, now));
		}
		return new ItemsResponse<>(items);
	}

	@Transactional
	public SaveScheduleResponse save(UUID groupId, CurrentUser user, SaveScheduleRequest req) {
		access.requireLeader(groupId, user);
		if (!Boolean.TRUE.equals(req.scheduleAgreed())) {
			throw ApiException.validation("scheduleAgreed", "Cần xác nhận lịch đã được nhóm thống nhất.");
		}
		ScheduleSpec spec = parseSpec(req.weekday(), req.cutoffTime(), req.timezone(), req.reportingWindowMinutes(), "");
		lockProject(groupId);
		Instant now = clock.instant();
		Anchor anchor = anchor(groupId, now);
		int versionNo = repo.latestSchedule(groupId).map(s -> s.versionNo() + 1).orElse(1);
		UUID scheduleId = repo.insertSchedule(groupId, versionNo, spec, anchor.start(), now, user.id());

		// Kỳ chưa mở: sinh lại ranh giới theo lịch mới (giữ id, số thứ tự, chuỗi nối). Kỳ đã mở/đóng giữ nguyên.
		List<CycleRow> unopened = anchor.unopened();
		if (!unopened.isEmpty()) {
			List<CyclePlan> plans = ScheduleCalculator.chain(spec, anchor.start(), null, unopened.size());
			repo.regenerate(scheduleId, unopened.stream().map(CycleRow::id).toList(), plans);
		}
		topUp(groupId, now);
		syncGroupTimezone(groupId, spec.zone());

		List<CycleRow> affected = repo.bySchedule(groupId, scheduleId);
		ScheduleRow saved = repo.findSchedule(groupId, scheduleId).orElseThrow();
		publishChanged(groupId, saved, affected, now);
		return new SaveScheduleResponse(CycleViews.toDto(saved, true, now),
				affected.stream().map(c -> CycleViews.toDto(c, now)).toList());
	}

	/** Đổi múi giờ lịch → đổi múi giờ nhóm, giữ nguyên NGÀY bắt đầu/deadline hiển thị (tính lại thời điểm theo múi giờ mới). */
	private void syncGroupTimezone(UUID groupId, ZoneId newZone) {
		Map<String, Object> row = jdbc.sql("SELECT timezone, start_at, deadline FROM projects WHERE id = :p")
				.param("p", groupId)
				.query((rs, i) -> Map.<String, Object>of("tz", rs.getString("timezone"),
						"start", DbTime.instant(rs, "start_at"), "deadline", DbTime.instant(rs, "deadline")))
				.single();
		ZoneId oldZone = ZoneId.of((String) row.get("tz"));
		if (oldZone.equals(newZone)) {
			return;
		}
		LocalDate startDate = ((Instant) row.get("start")).atZone(oldZone).toLocalDate();
		LocalDate deadlineDate = ((Instant) row.get("deadline")).atZone(oldZone).toLocalDate();
		jdbc.sql("""
				UPDATE projects SET timezone = :tz, start_at = :s, deadline = :d, version = version + 1 WHERE id = :p
				""")
				.param("tz", newZone.getId()).param("s", DbTime.toDb(startDate.atStartOfDay(newZone).toInstant()))
				.param("d", DbTime.toDb(endOfDay(deadlineDate, newZone))).param("p", groupId).update();
	}

	/** Deadline nhóm hết hiệu lực lúc 23:59:59.999 của ngày đó theo múi giờ nhóm. */
	public static Instant endOfDay(LocalDate date, ZoneId zone) {
		return date.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1);
	}

	private void publishChanged(UUID groupId, ScheduleRow schedule, List<CycleRow> affected, Instant now) {
		CycleRow anchorCycle = affected.isEmpty() ? repo.last(groupId).orElseThrow() : affected.get(0);
		String summary = ScheduleLabels.summary(schedule.spec(), now);
		notifications.publish(new NotificationEvent(groupId, "CHECKPOINT_SCHEDULE_CHANGED", "CHECKPOINT_CYCLE",
				anchorCycle.id(), anchorCycle.id(), "schedule:" + schedule.id() + ":changed", access.activeMemberIds(groupId),
				"Lịch checkpoint của nhóm đã thay đổi",
				"Lịch mới: " + summary + ". Chỉ áp dụng cho các kỳ chưa mở; kỳ đang mở và kỳ đã đóng giữ lịch cũ.",
				Map.of("scheduleId", schedule.id().toString(), "subjectLabel", "Lịch phiên bản " + schedule.versionNo())));
	}
}
