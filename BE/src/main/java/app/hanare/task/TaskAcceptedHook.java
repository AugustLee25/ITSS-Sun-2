package app.hanare.task;

import java.time.Instant;
import java.util.UUID;

/**
 * CỔNG CHUNG: BE-B1 ({@code task}) gọi MỌI bean {@code TaskAcceptedHook} ngay sau khi lần nộp được TL chấp nhận
 * ({@code ACCEPTED}, task → {@code DONE}) trong CÙNG transaction của {@code POST /task-submissions/{id}/review}.
 * BE-B2 cài đặt: ghi đúng một khoản điểm {@code AWARD} cho người có quyền nhận hiện tại ({@code points}) và chuyển
 * {@code support_works}/contribution sang xác nhận ({@code help}). Phải idempotent theo {@code submissionId}/{@code taskId}.
 */
public interface TaskAcceptedHook {

	void onSubmissionAccepted(AcceptedSubmission accepted);

	/**
	 * @param submittedBy người nộp bản được chấp nhận (có thể là Helper)
	 * @param submittedAt thời điểm nộp của phiên bản được chấp nhận (dùng xét đúng hạn)
	 * @param reviewedAt  thời điểm TL duyệt (riêng)
	 */
	record AcceptedSubmission(
			UUID projectId,
			UUID taskId,
			UUID roundId,
			UUID submissionId,
			UUID reviewerId,
			UUID submittedBy,
			Instant submittedAt,
			Instant reviewedAt) {
	}
}
