package app.hanare.points;

import app.hanare.points.Refs.CycleRef;
import app.hanare.points.Refs.TaskRef;
import app.hanare.points.Refs.UserRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTO của nhóm endpoint K (khớp api-types.ts mục ĐIỂM). */
public final class PointDtos {

	private PointDtos() {
	}

	public record PointPolicyDto(UUID id, int versionNo, String status, PointAmount totalPoints,
			Map<String, Integer> weights, int memberCountAtBaseline, UserRef approvedBy, Instant approvedAt,
			Instant createdAt) {
	}

	public record AllocationTask(UUID taskId, String title, String difficulty, int weight, PointAmount points) {
	}

	public record AllocationMember(UserRef user, PointAmount initialPoints, PointAmount reservePoints,
			PointAmount pricedPoints, int weightSum, int taskCount, List<AllocationTask> tasks, List<String> warnings) {
	}

	public record Allocation(PointAmount totalPoints, List<AllocationMember> members, PointAmount unallocatedPoints) {
	}

	public record PolicyIssue(String code, String message, UUID taskId, UUID userId) {
	}

	public record PointPolicyResponse(PointPolicyDto policy, boolean active, String statusLabel, Allocation allocation) {
	}

	public record ReserveInput(UUID userId, Long reservePointUnits) {
	}

	public record PointPolicyPreviewRequest(List<UUID> participantUserIds, List<ReserveInput> reserves) {
	}

	public record ActivatePointPolicyRequest(List<UUID> participantUserIds, List<ReserveInput> reserves,
			Boolean confirmedWithMembers) {

		public PointPolicyPreviewRequest asPreview() {
			return new PointPolicyPreviewRequest(participantUserIds, reserves);
		}
	}

	public record SumCheck(long expectedUnits, long actualUnits, boolean ok) {
	}

	public record PointPolicyPreviewResponse(String basis, int nextVersionNo, int memberCount, Allocation allocation,
			SumCheck sumCheck, List<PolicyIssue> blockingIssues, boolean canActivate) {
	}

	public record ActivatePointPolicyResponse(PointPolicyDto policy, Allocation allocation, int fundedTaskCount) {
	}

	public record PointBreakdownCount(PointAmount points, int taskCount) {
	}

	public record PendingTransfer(PointAmount heldPoints, int openRequestCount) {
	}

	public record MyPointsResponse(boolean policyActive, String statusLabel, Integer memberCount, PointAmount initialPoints,
			PointAmount confirmedPoints, PointAmount pendingPoints, PointAmount potentialPoints,
			PointBreakdownCount transferredOut, PointBreakdownCount takenOver, PendingTransfer pendingTransfer,
			PointAmount unallocatedReservePoints, String goalCompletionPercentLabel) {
	}

	public record PointTaskRow(UUID taskId, String title, String difficulty, PointAmount points,
			UserRef originalAssignee, UserRef currentRecipient, String source, CycleRef cycle, Instant deadline,
			String status, String statusLabel, UserRef transferredTo, boolean heldForRequest, UUID helpRequestId,
			UUID workId) {
	}

	public record PointHistoryItem(UUID id, String group, String kind, Instant occurredAt, TaskRef task,
			PointAmount delta, PointAmount runningTotal, String source, UserRef approvedBy, String label, String reason,
			UUID ledgerEntryId, UUID workId) {
	}

	public record SettlementInfo(UUID id, UserRef approvedBy, Instant approvedAt, Instant revokedAt, String revokeReason) {
	}

	public record LedgerEntryDetail(PointHistoryItem entry, TaskRef task, PointAmount budgetPoints,
			UserRef originalAssignee, TaskSubmissionDto submission, ReviewDto review, ContributionDto contribution,
			SettlementInfo settlement, UUID workId, String entryType, UUID reversesEntryId, UUID reversedByEntryId) {
	}

	public record EvidenceItem(String type, String url, String label) {
	}

	public record ReviewDto(UUID id, UUID submissionId, String decision, UserRef reviewer, String issueDescription,
			Instant reviewedAt) {
	}

	public record TaskSubmissionDto(UUID id, UUID roundId, UUID taskId, UUID planRevisionId, int submissionNo,
			UserRef submittedBy, String resultNote, List<EvidenceItem> evidence, Instant submittedAt, Instant deadline,
			boolean isOnTime, boolean isReviewTarget, ReviewDto review, UUID supportSubmissionId) {
	}

	public record ContributionDto(UUID id, UUID workId, UserRef helper, String status, UUID acceptedSubmissionId,
			UserRef confirmedBy, Instant confirmedAt, UUID ledgerEntryId) {
	}

	public record SimulationCandidate(UUID taskId, String title, String difficulty, PointAmount points, UserRef owner,
			UUID helpRequestId) {
	}

	public record SimulationCandidatesResponse(List<SimulationCandidate> transferable, List<SimulationCandidate> takeable) {
	}

	public record SimulatePointsRequest(List<UUID> transferOutTaskIds, List<UUID> takeOnTaskIds) {
	}

	public record SimulationStep(String kind, String label, UUID taskId, PointAmount delta, PointAmount potentialAfter) {
	}

	public record SimulatePointsResponse(PointAmount confirmedPoints, PointAmount startPotentialPoints,
			PointAmount endPotentialPoints, List<SimulationStep> steps, String notice) {
	}

	public record GroupPointsSummary(boolean policyActive, String statusLabel, PointAmount totalPoints,
			PointAmount confirmedPoints, PointAmount pendingPoints, PointAmount unallocatedPoints) {
	}

	public record ReverseSettlementRequest(String reason, UUID reissueRecipientUserId) {
	}

	public record ReverseSettlementResponse(UUID settlementId, UUID reversalEntryId, UUID newSettlementId,
			UUID newLedgerEntryId, TaskReader.TaskSummaryView task) {
	}
}
