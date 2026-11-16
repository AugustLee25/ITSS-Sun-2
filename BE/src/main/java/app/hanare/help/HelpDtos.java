package app.hanare.help;

import app.hanare.points.PointAmount;
import app.hanare.points.PointDtos.ContributionDto;
import app.hanare.points.PointDtos.EvidenceItem;
import app.hanare.points.Refs.ReasonRef;
import app.hanare.points.Refs.TaskRef;
import app.hanare.points.Refs.UserRef;
import app.hanare.points.TaskReader.TaskSummaryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO nhóm endpoint J — hỗ trợ công khai, offer, bàn giao (khớp api-types.ts). */
public final class HelpDtos {

	private HelpDtos() {
	}

	public enum SupportWorkResolution {
		RESUME, REOPEN_REQUEST, CANCEL_REQUEST
	}

	// ------------------------------------------------------------------------------------------------ request

	public record CreateHelpRequestRequest(@NotNull UUID taskId, @NotNull UUID beneficiaryId, UUID supportRequestId,
			@NotNull @Size(min = 3, max = 120) String title, @NotNull @Size(min = 10, max = 2000) String description,
			@Size(max = 4000) String expectedOutput, Integer estimatedEffortMinutes, @Size(max = 1000) String skillNotes,
			@NotNull Boolean privacyChecked) {
	}

	public record UpdateHelpRequestRequest(@Size(min = 3, max = 120) String title,
			@Size(min = 10, max = 2000) String description, @Size(max = 4000) String expectedOutput,
			Integer estimatedEffortMinutes, @Size(max = 1000) String skillNotes, @NotNull Long version) {
	}

	public record CancelHelpRequestRequest(@Size(max = 1000) String reason, @NotNull Long version) {
	}

	public record CreateOfferRequest(@Size(max = 500) String message, @NotNull Boolean acknowledgedScope,
			@NotNull Long helpRequestVersion) {
	}

	public record ReconfirmOfferRequest(@NotNull Long helpRequestVersion) {
	}

	public record AcceptOfferRequest(@NotNull UUID offerId, @NotNull Long version) {
	}

	public record SubmitSupportResultRequest(@NotNull @Size(min = 10, max = 1000) String resultNote,
			@Valid List<EvidenceInput> evidence, Integer actualMinutes) {
	}

	public record EvidenceInput(String type, @NotBlank @Size(max = 2048) String url, @Size(max = 200) String label) {
	}

	public record ReportUnableRequest(@NotNull @Size(min = 5, max = 1000) String reason, @NotNull Long version) {
	}

	public record ResolveDiscussionRequest(@NotNull SupportWorkResolution resolution,
			@NotNull @Size(min = 5, max = 1000) String note, @NotNull Long version) {
	}

	// ------------------------------------------------------------------------------------------------ response

	public record SupportOfferDto(UUID id, UUID helpRequestId, UserRef helper, String message, String status,
			boolean needsReconfirmation, Instant createdAt, Instant decidedAt, UserRef decidedBy) {
	}

	public record PointImpact(PointAmount potentialBefore, PointAmount potentialAfter, PointAmount delta) {
	}

	public record SupportWorkSummary(UUID id, UUID helpRequestId, String title, UserRef helper, UserRef requester,
			String status, String helpRequestStatus, String statusLabel, PointAmount points, boolean pointRecorded,
			String contributionStatus, Instant updatedAt) {
	}

	public record HelpRequestCard(UUID id, UUID groupId, String title, String descriptionPreview, String expectedOutput,
			TaskRef task, UserRef postedBy, UserRef beneficiary, Instant deadline, String difficulty,
			Integer estimatedEffortMinutes, PointAmount points, String pointLabel, String status, String statusLabel,
			String myRoleInRequest, String myOfferStatus, UUID workId, Instant createdAt) {
	}

	public record HelpRequestDetail(UUID id, UUID groupId, String title, String descriptionPreview, String expectedOutput,
			TaskRef task, UserRef postedBy, UserRef beneficiary, Instant deadline, String difficulty,
			Integer estimatedEffortMinutes, PointAmount points, String pointLabel, String status, String statusLabel,
			String myRoleInRequest, String myOfferStatus, UUID workId, Instant createdAt, String description,
			String skillNotes, UUID supportRequestId, UserRef resultApprover, SupportOfferDto myOffer,
			List<SupportOfferDto> offers, SupportWorkSummary work, PointImpact myImpact, String pointExplanation,
			List<String> allowedActions, long version) {
	}

	public record SupportSubmissionDto(UUID id, UUID workId, int revisionNo, String resultNote, List<EvidenceItem> evidence,
			Integer actualMinutes, Instant submittedAt, UUID taskSubmissionId, String reviewDecision,
			String issueDescription) {
	}

	public record SupportTimelineEvent(Instant at, String kind, UserRef actor, String note) {
	}

	public record SupportWorkDto(UUID id, UUID helpRequestId, String title, UserRef helper, UserRef requester, String status,
			String helpRequestStatus, String statusLabel, PointAmount points, boolean pointRecorded,
			String contributionStatus, Instant updatedAt, UUID groupId, TaskRef task, UserRef originalAssignee,
			UserRef resultApprover, String scopeSnapshot, String expectedOutput, Instant deadlineSnapshot,
			String difficultySnapshot, String pointLabel, UUID roundId, List<SupportSubmissionDto> submissions,
			List<SupportTimelineEvent> timeline, ContributionDto contribution, UUID latestTaskSubmissionId,
			Instant cutoffAt, boolean canSubmit, ReasonRef cannotSubmitReason, boolean canReviewNow,
			UUID successorHelpRequestId, List<String> allowedActions, long version) {
	}

	public record AcceptOfferResponse(HelpRequestDetail helpRequest, SupportWorkDto work, TaskSummaryView task,
			List<UUID> notSelectedOfferIds) {
	}

	public record SubmitSupportResultResponse(SupportSubmissionDto submission, SupportWorkDto work) {
	}
}
