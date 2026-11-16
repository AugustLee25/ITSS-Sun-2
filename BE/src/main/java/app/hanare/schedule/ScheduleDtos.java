package app.hanare.schedule;

import app.hanare.group.UserRef;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTO của module schedule (khớp {@code docs/api-types.ts}). */
public final class ScheduleDtos {

	private ScheduleDtos() {
	}

	/** Phần lịch dùng chung cho tạo nhóm. {@code weekday}/{@code timezone} kiểm tra nghiệp vụ → 422. */
	public record ScheduleInput(
			@NotNull(message = "Chọn ngày chốt trong tuần.") Integer weekday,
			@NotNull(message = "Nhập giờ chốt (HH:mm).") String cutoffTime,
			@NotNull(message = "Chọn múi giờ.") String timezone,
			Integer reportingWindowMinutes) {
	}

	public record SchedulePreviewRequest(
			UUID groupId,
			LocalDate startDate,
			@NotNull(message = "Chọn ngày chốt trong tuần.") Integer weekday,
			@NotNull(message = "Nhập giờ chốt (HH:mm).") String cutoffTime,
			@NotNull(message = "Chọn múi giờ.") String timezone,
			Integer reportingWindowMinutes) {
	}

	public record SaveScheduleRequest(
			@NotNull(message = "Chọn ngày chốt trong tuần.") Integer weekday,
			@NotNull(message = "Nhập giờ chốt (HH:mm).") String cutoffTime,
			@NotNull(message = "Chọn múi giờ.") String timezone,
			Integer reportingWindowMinutes,
			@NotNull(message = "Cần xác nhận lịch đã được nhóm thống nhất.") Boolean scheduleAgreed) {
	}

	public record CheckpointScheduleDto(
			UUID id,
			int versionNo,
			int weekday,
			String cutoffTime,
			String timezone,
			int reminderBeforeMinutes,
			Integer reportingWindowMinutes,
			Instant effectiveFrom,
			Instant agreedAt,
			UserRef recordedBy,
			@JsonProperty("isCurrent") boolean isCurrent,
			String summaryLabel) {
	}

	public record CheckpointCycleDto(
			UUID id,
			UUID groupId,
			UUID scheduleId,
			int sequenceNo,
			Instant periodStartAt,
			Instant opensAt,
			Instant reminderAt,
			Instant closesAt,
			String status,
			String phase,
			String phaseLabel,
			Instant finalizedAt,
			@JsonProperty("isCurrent") boolean isCurrent,
			boolean reportWindowOpen,
			String timezone,
			String label) {
	}

	/** {@code CheckpointCycleDetail} = {@code CheckpointCycle} + các trường tổng hợp. */
	public record CheckpointCycleDetailDto(
			UUID id,
			UUID groupId,
			UUID scheduleId,
			int sequenceNo,
			Instant periodStartAt,
			Instant opensAt,
			Instant reminderAt,
			Instant closesAt,
			String status,
			String phase,
			String phaseLabel,
			Instant finalizedAt,
			@JsonProperty("isCurrent") boolean isCurrent,
			boolean reportWindowOpen,
			String timezone,
			String label,
			Instant serverTime,
			UUID previousCycleId,
			UUID nextCycleId,
			int activeMemberCount,
			int reportedMemberCount,
			int notRespondedMemberCount,
			Object leadSummary) {
	}

	public record SchedulePreviewCycle(
			int sequenceNo,
			Instant periodStartAt,
			Instant opensAt,
			Instant reminderAt,
			Instant closesAt,
			long periodHours,
			@JsonProperty("isTransition") boolean isTransition,
			String label) {
	}

	public record ScheduleWarning(String code, String message) {
	}

	public record SchedulePreviewResponse(
			String summaryLabel,
			int reminderBeforeMinutes,
			List<SchedulePreviewCycle> cycles,
			List<ScheduleWarning> warnings) {
	}

	public record SaveScheduleResponse(CheckpointScheduleDto schedule, List<CheckpointCycleDto> affectedCycles) {
	}
}
