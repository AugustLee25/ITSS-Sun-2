package app.hanare.help;

import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.PointMath;
import app.hanare.security.GroupAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Thông báo dùng chung của gói help (không chứa nội dung riêng tư). */
@Component
public class HelpNotices {

	private final NotificationService notifications;
	private final GroupAccess access;

	public HelpNotices(NotificationService notifications, GroupAccess access) {
		this.notifications = notifications;
		this.access = access;
	}

	/** HELP_PUBLISHED tới mọi thành viên trừ người đăng: `Task T · Khó · +10,0000 điểm % khi TL duyệt`. */
	public void helpPublished(UUID projectId, UUID helpRequestId, String taskTitle, String difficulty, Long units,
			UUID poster) {
		List<UUID> recipients = access.activeMemberIds(projectId).stream().filter(u -> !u.equals(poster)).toList();
		String message = taskTitle + " · " + PointMath.difficultyLabel(difficulty)
				+ (units == null ? "" : " · +" + PointMath.label(units) + " điểm % khi TL duyệt");
		notifications.publish(new NotificationEvent(projectId, "HELP_PUBLISHED", "HELP_REQUEST", helpRequestId, null,
				"help:" + helpRequestId + ":published", recipients, "Có phần việc cần hỗ trợ", message,
				Map.of("helpRequestId", helpRequestId.toString())));
	}
}
