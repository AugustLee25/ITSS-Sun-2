package app.hanare.group;

import app.hanare.schedule.ScheduleDtos.CheckpointCycleDto;
import app.hanare.schedule.ScheduleDtos.CheckpointScheduleDto;
import app.hanare.schedule.ScheduleDtos.ScheduleInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** DTO của module group (khớp {@code docs/api-types.ts}). */
public final class GroupDtos {

	private GroupDtos() {
	}

	public record GroupDto(UUID id, String name, String courseName, LocalDate startDate, LocalDate deadline,
			String timezone, int memberCount, UserRef leader, String myRole, Instant createdAt, long version) {
	}

	public record GroupCardAttention(int count, String label) {
	}

	public record GroupCardDto(UUID id, String name, String courseName, int memberCount, LocalDate deadline,
			String myRole, GroupCardAttention attention, long unreadNotifications, Instant lastActivityAt) {
	}

	public record CreateGroupRequest(
			@NotNull(message = "Nhập tên nhóm.") @Size(min = 3, max = 80, message = "Tên nhóm dài 3–80 ký tự.") String name,
			@Size(max = 120, message = "Tên môn tối đa 120 ký tự.") String courseName,
			LocalDate startDate,
			@NotNull(message = "Chọn deadline chung của nhóm.") LocalDate deadline,
			@NotNull(message = "Chọn lịch checkpoint.") @Valid ScheduleInput schedule,
			@NotNull(message = "Cần xác nhận lịch đã được nhóm thống nhất.") Boolean scheduleAgreed) {
	}

	public record CreateGroupResponse(GroupDto group, CheckpointScheduleDto schedule, CheckpointCycleDto currentCycle,
			InviteDto invite) {
	}

	public record MemberDto(UUID userId, String name, String role, Instant joinedAt) {
	}

	public record InviteDto(String code, String status, String sharePath, UserRef createdBy, Instant createdAt,
			Instant expiresAt) {
	}

	public record InvitePreviewDto(String code, String status, String groupName, String courseName, UserRef leader,
			int memberCount, Instant expiresAt, Boolean alreadyMember, UUID groupId) {
	}

	public record AcceptInviteResponse(GroupDto group) {
	}
}
