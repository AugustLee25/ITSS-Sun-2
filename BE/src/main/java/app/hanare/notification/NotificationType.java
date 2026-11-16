package app.hanare.notification;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 25 loại thông báo (19 của spec 13 + 6 mở rộng), đích mặc định và mức ưu tiên — khớp
 * {@code NOTIFICATION_TARGET_SCREEN}/{@code NOTIFICATION_PRIORITY} trong {@code docs/api-types.ts}.
 */
public enum NotificationType {

	TASK_ASSIGNED("S05", Priority.ACTION_REQUIRED),
	PLAN_SUBMITTED("S05", Priority.ACTION_REQUIRED),
	PLAN_CHANGE_SUBMITTED("S05", Priority.ACTION_REQUIRED),
	PLAN_APPROVED("S05", Priority.RELEVANT_CHANGE),
	PLAN_CHANGES_REQUESTED("S05", Priority.ACTION_REQUIRED),
	CHECKPOINT_REMINDER("S06", Priority.ACTION_REQUIRED),
	CHECKPOINT_REVIEW_REQUIRED("S03", Priority.ACTION_REQUIRED),
	CHECKPOINT_SCHEDULE_CHANGED("S03", Priority.RELEVANT_CHANGE),
	NEED_HELP("S07", Priority.ACTION_REQUIRED),
	SUPPORT_DECISION("S07", Priority.RELEVANT_CHANGE),
	HELP_PUBLISHED("S09", Priority.RELEVANT_CHANGE),
	RISK_RELEVANT("S05", Priority.RELEVANT_CHANGE),
	SUPPORT_OFFERED("S09", Priority.ACTION_REQUIRED),
	SUPPORT_ACCEPTED("S10", Priority.RELEVANT_CHANGE),
	SUPPORT_SUBMITTED("S11", Priority.ACTION_REQUIRED),
	REVISION_REQUESTED("S10", Priority.ACTION_REQUIRED),
	CONTRIBUTION_CONFIRMED("S12", Priority.INFORMATIONAL),
	POINTS_AWARDED("S12", Priority.INFORMATIONAL),
	POINT_ASSIGNMENT_CHANGED("S12", Priority.RELEVANT_CHANGE),
	TASK_REVIEWED("S05", Priority.RELEVANT_CHANGE),
	TASK_CARRIED_OVER("S05", Priority.ACTION_REQUIRED),
	SUPPORT_NOT_SELECTED("S09", Priority.RELEVANT_CHANGE),
	SUPPORT_WORK_ISSUE("S10", Priority.ACTION_REQUIRED),
	POINTS_ADJUSTED("S12", Priority.INFORMATIONAL),
	POINT_POLICY_ACTIVATED("S12", Priority.RELEVANT_CHANGE);

	/** {@code NotificationPriority} (7.1). */
	public enum Priority {
		ACTION_REQUIRED, RELEVANT_CHANGE, INFORMATIONAL
	}

	private static final List<String> ACTION_REQUIRED_NAMES = Arrays.stream(values())
			.filter(t -> t.priority == Priority.ACTION_REQUIRED).map(Enum::name).toList();
	private static final Set<String> ALL_NAMES = Arrays.stream(values()).map(Enum::name).collect(Collectors.toSet());

	private final String screen;
	private final Priority priority;

	NotificationType(String screen, Priority priority) {
		this.screen = screen;
		this.priority = priority;
	}

	public String screen() {
		return screen;
	}

	public Priority priority() {
		return priority;
	}

	/** Tên các loại thuộc tab "Cần hành động". */
	static List<String> actionRequiredNames() {
		return ACTION_REQUIRED_NAMES;
	}

	static boolean isKnown(String name) {
		return ALL_NAMES.contains(name);
	}
}
