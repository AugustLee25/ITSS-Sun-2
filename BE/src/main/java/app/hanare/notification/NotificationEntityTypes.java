package app.hanare.notification;

import java.util.Set;

/** {@code NotificationEntityType} (§2.6). */
final class NotificationEntityTypes {

	static final Set<String> ALL = Set.of("GROUP", "TASK", "PLAN_REVISION", "CHECKPOINT_CYCLE", "SUPPORT_REQUEST",
			"HELP_REQUEST", "SUPPORT_WORK", "LEDGER_ENTRY", "RISK_REPORT", "POINT_POLICY");

	private NotificationEntityTypes() {
	}

	static boolean isKnown(String name) {
		return name != null && ALL.contains(name);
	}
}
