package app.hanare.support;

import app.hanare.points.Refs.UserRef;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** DTO nhóm endpoint I — hỗ trợ riêng & risk (khớp api-types.ts). */
public final class SupportDtos {

	private SupportDtos() {
	}

	public enum ObstacleType {
		TECHNICAL, TIME, SCOPE, SKILL, PERSONAL, OTHER
	}

	public enum SupportRequestLeadAction {
		RESOLVED_PRIVATELY, NEED_MORE_DISCUSSION
	}

	public enum RiskType {
		DELAY_RISK, BLOCKED, DROPOUT_RISK
	}

	public record CreateSupportRequestRequest(@NotNull ObstacleType obstacleType,
			@NotNull @Size(min = 10, max = 2000) String description,
			@NotNull @Size(min = 5, max = 1000) String neededSupport, @Size(max = 1000) String impact,
			LocalDate expectedDate, UUID roundId) {
	}

	public record SupportRequestDecisionRequest(@NotNull SupportRequestLeadAction action,
			@NotBlank @Size(min = 1, max = 2000) String response, @NotNull Long version) {
	}

	public record SupportRequestSummary(UUID id, UUID taskId, String taskTitle, UserRef sender, String obstacleType,
			String status, Instant createdAt, Instant updatedAt) {
	}

	/** CHỈ người gửi và TL đọc được. */
	public record SupportRequestDto(UUID id, UUID taskId, String taskTitle, UserRef sender, String obstacleType,
			String status, Instant createdAt, Instant updatedAt, UUID groupId, UUID roundId, String description,
			String neededSupport, String impact, LocalDate expectedDate, String decision, String leadResponse,
			UserRef reviewedBy, Instant reviewedAt, List<UUID> publishedHelpRequestIds, long version,
			List<String> allowedActions) {
	}

	public record CreateRiskReportRequest(@NotNull RiskType riskType, UUID taskId, UUID subjectUserId,
			@NotNull @Size(min = 5, max = 500) String publicSummary, List<UUID> affectedTaskIds) {
	}

	public record ResolveRiskRequest(@Size(max = 1000) String note, @NotNull Long version) {
	}

	public record RiskReportDto(UUID id, UUID groupId, UUID taskId, String taskTitle, UserRef subject, UserRef reportedBy,
			String riskType, String publicSummary, String status, UserRef confirmedBy, Instant confirmedAt,
			Instant resolvedAt, List<UUID> affectedTaskIds, Instant createdAt, long version) {
	}
}
