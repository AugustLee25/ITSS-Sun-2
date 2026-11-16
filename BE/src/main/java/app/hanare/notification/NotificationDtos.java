package app.hanare.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTO của module notification (khớp {@code docs/api-types.ts}). */
public final class NotificationDtos {

	private NotificationDtos() {
	}

	/** Mục tiêu điều hướng; {@code params} chỉ chứa id cần để mở màn hình (bảng §1.12). */
	public record ScreenTarget(String screen, Map<String, String> params) {
	}

	public record NotificationItem(
			UUID id,
			String type,
			String priority,
			UUID groupId,
			String groupName,
			String title,
			String message,
			String subjectLabel,
			String entityType,
			UUID entityId,
			UUID cycleId,
			ScreenTarget target,
			@JsonProperty("isRead") boolean isRead,
			Instant readAt,
			Instant createdAt) {
	}

	public record UnreadByGroup(UUID groupId, long count) {
	}

	public record UnreadCountResponse(long total, long actionRequired, List<UnreadByGroup> byGroup) {
	}

	public record OpenNotificationResponse(
			NotificationItem notification,
			boolean available,
			ScreenTarget target,
			String unavailableMessage,
			ScreenTarget fallback) {
	}

	public record MarkAllReadRequest(UUID groupId, String tab) {
	}

	public record MarkAllReadResponse(long updated) {
	}
}
