package app.hanare.notification;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Một sự kiện thông báo (CỔNG CHUNG). {@code eventType}/{@code entityType} dùng đúng chuỗi ở
 * {@code docs/API_CONTRACT.md} §3 / {@code NotificationType} trong {@code docs/api-types.ts}.
 *
 * @param projectId    nhóm (project) của sự kiện
 * @param eventType    vd. {@code PLAN_SUBMITTED}, {@code CHECKPOINT_REMINDER}
 * @param entityType   vd. {@code PLAN_REVISION}, {@code CHECKPOINT_CYCLE}
 * @param entityId     id thực thể nguồn (đích điều hướng do BE-A dựng từ eventType + entity + payload)
 * @param cycleId      kỳ liên quan, có thể {@code null}
 * @param dedupKey     khóa chống trùng toàn cục, vd. {@code checkpoint:{cycleId}:reminder}
 * @param recipientIds người nhận (phải là thành viên nhóm); danh sách rỗng = chỉ ghi sự kiện
 * @param title        tiêu đề ngắn (tiếng Việt, trung lập, không lộ nội dung riêng tư)
 * @param message      nội dung (tiếng Việt)
 * @param payload      dữ liệu điều hướng/hiển thị (vd. taskId, planId); KHÔNG chứa nội dung riêng tư
 */
public record NotificationEvent(
		UUID projectId,
		String eventType,
		String entityType,
		UUID entityId,
		UUID cycleId,
		String dedupKey,
		Collection<UUID> recipientIds,
		String title,
		String message,
		Map<String, Object> payload) {
}
