package app.hanare.notification;

import app.hanare.group.PageResponse;
import app.hanare.notification.NotificationDtos.MarkAllReadRequest;
import app.hanare.notification.NotificationDtos.MarkAllReadResponse;
import app.hanare.notification.NotificationDtos.NotificationItem;
import app.hanare.notification.NotificationDtos.OpenNotificationResponse;
import app.hanare.notification.NotificationDtos.UnreadCountResponse;
import app.hanare.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mục E của hợp đồng: thông báo in-app của người dùng hiện tại. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

	private final NotificationQueryService service;

	NotificationController(NotificationQueryService service) {
		this.service = service;
	}

	@GetMapping
	PageResponse<NotificationItem> list(@RequestParam(required = false) UUID groupId,
			@RequestParam(required = false) String tab, @RequestParam(required = false) Boolean unreadOnly,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			CurrentUser user) {
		return service.list(user, groupId, tab, unreadOnly, page, size);
	}

	@GetMapping("/unread-count")
	UnreadCountResponse unreadCount(@RequestParam(required = false) UUID groupId, CurrentUser user) {
		return service.unreadCount(user, groupId);
	}

	@PatchMapping("/{notificationId}/read")
	NotificationItem markRead(@PathVariable UUID notificationId, CurrentUser user) {
		return service.markRead(user, notificationId);
	}

	@PostMapping("/{notificationId}/open")
	OpenNotificationResponse open(@PathVariable UUID notificationId, CurrentUser user) {
		return service.open(user, notificationId);
	}

	@PostMapping("/read-all")
	MarkAllReadResponse readAll(@RequestBody(required = false) MarkAllReadRequest request, CurrentUser user) {
		return service.readAll(user, request);
	}
}
