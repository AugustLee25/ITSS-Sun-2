package app.hanare.schedule;

import app.hanare.group.UserRef;
import app.hanare.schedule.CycleRepository.CycleRow;
import app.hanare.schedule.CycleRepository.ScheduleRow;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDetailDto;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDto;
import app.hanare.schedule.ScheduleDtos.CheckpointScheduleDto;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Dựng DTO từ dòng DB (nhãn tiếng Việt, cờ theo "bây giờ"). */
final class CycleViews {

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private CycleViews() {
	}

	private static boolean current(CycleRow r, Instant now) {
		return !"CANCELLED".equals(r.status()) && r.periodStartAt().isBefore(now) && !now.isAfter(r.closesAt());
	}

	private static boolean windowOpen(CycleRow r, Instant now) {
		return !"CANCELLED".equals(r.status()) && !now.isBefore(r.opensAt()) && !now.isAfter(r.closesAt());
	}

	static String label(CycleRow r) {
		return ScheduleLabels.cycleLabel(r.sequenceNo(), r.periodStartAt(), r.closesAt(), ZoneId.of(r.timezone()));
	}

	static CheckpointCycleDto toDto(CycleRow r, Instant now) {
		return new CheckpointCycleDto(r.id(), r.projectId(), r.scheduleId(), r.sequenceNo(),
				ScheduleLabels.ms(r.periodStartAt()), ScheduleLabels.ms(r.opensAt()), ScheduleLabels.ms(r.reminderAt()),
				ScheduleLabels.ms(r.closesAt()), r.status(), ScheduleLabels.phase(r.status()),
				ScheduleLabels.phaseLabel(r.status()), ScheduleLabels.ms(r.finalizedAt()), current(r, now),
				windowOpen(r, now), r.timezone(), label(r));
	}

	static CheckpointCycleDetailDto toDetail(CycleRow r, Instant now, UUID nextCycleId, int active, int reported,
			Object leadSummary) {
		return new CheckpointCycleDetailDto(r.id(), r.projectId(), r.scheduleId(), r.sequenceNo(),
				ScheduleLabels.ms(r.periodStartAt()), ScheduleLabels.ms(r.opensAt()), ScheduleLabels.ms(r.reminderAt()),
				ScheduleLabels.ms(r.closesAt()), r.status(), ScheduleLabels.phase(r.status()),
				ScheduleLabels.phaseLabel(r.status()), ScheduleLabels.ms(r.finalizedAt()), current(r, now),
				windowOpen(r, now), r.timezone(), label(r), ScheduleLabels.ms(now), r.previousCycleId(), nextCycleId,
				active, reported, Math.max(0, active - reported), leadSummary);
	}

	static CheckpointScheduleDto toDto(ScheduleRow r, boolean isCurrent, Instant now) {
		return new CheckpointScheduleDto(r.id(), r.versionNo(), r.weekday(), r.cutoff().format(HH_MM), r.timezone(),
				r.reminderBeforeMinutes(), r.windowMinutes(), ScheduleLabels.ms(r.effectiveFrom()),
				ScheduleLabels.ms(r.agreedAt()), new UserRef(r.recordedBy(), r.recordedByName()), isCurrent,
				ScheduleLabels.summary(r.spec(), now));
	}
}
