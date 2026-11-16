package app.hanare.notification;

import app.hanare.notification.NotificationDtos.ScreenTarget;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Dựng {@code ScreenTarget} cho thông báo từ bảng §1.12/§3 (loại → màn hình + tham số) và kiểm tra lại khi mở.
 *
 * <p>Tham số lấy từ {@code entityType/entityId} và {@code payload} của sự kiện. Module phát sự kiện cần đưa vào payload:
 * {@code taskId} (loại đích S05, kể cả khi entity là PLAN_REVISION/RISK_REPORT; và {@code POINT_ASSIGNMENT_CHANGED}),
 * {@code planId} khi entity không phải PLAN_REVISION, {@code subjectLabel} (tên task/yêu cầu hiển thị dưới tiêu đề).
 */
@Component
class NotificationTargets {

	static final String UNAVAILABLE_MESSAGE = "Nội dung này không còn khả dụng";

	private final JdbcClient jdbc;
	private final GroupAccess access;

	NotificationTargets(JdbcClient jdbc, GroupAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	private static String text(JsonNode payload, String key) {
		JsonNode v = payload == null ? null : payload.get(key);
		return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString();
	}

	private static void put(Map<String, String> params, String key, Object value) {
		if (value != null) {
			params.put(key, value.toString());
		}
	}

	static ScreenTarget target(NotificationType type, UUID projectId, String entityType, UUID entityId, UUID cycleId,
			JsonNode payload) {
		Map<String, String> p = new LinkedHashMap<>();
		p.put("groupId", projectId.toString());
		switch (type.screen()) {
			case "S03" -> {
				if (type == NotificationType.CHECKPOINT_REVIEW_REQUIRED) {
					put(p, "cycleId", cycleId != null ? cycleId : "CHECKPOINT_CYCLE".equals(entityType) ? entityId : null);
				}
			}
			case "S05" -> {
				put(p, "taskId", firstNonNull(text(payload, "taskId"), "TASK".equals(entityType) ? entityId : null));
				put(p, "planId", firstNonNull(text(payload, "planId"), "PLAN_REVISION".equals(entityType) ? entityId : null));
			}
			case "S06" -> put(p, "cycleId", firstNonNull(cycleId, "CHECKPOINT_CYCLE".equals(entityType) ? entityId : null));
			case "S07" -> {
				Object request = firstNonNull(text(payload, "supportRequestId"),
						"SUPPORT_REQUEST".equals(entityType) ? entityId : null);
				if (request != null) {
					put(p, "supportRequestId", request);
				}
				else {
					put(p, "taskId", firstNonNull(text(payload, "taskId"), "TASK".equals(entityType) ? entityId : null));
				}
			}
			case "S09" -> put(p, "helpRequestId",
					firstNonNull(text(payload, "helpRequestId"), "HELP_REQUEST".equals(entityType) ? entityId : null));
			case "S10", "S11" -> put(p, "workId",
					firstNonNull(text(payload, "workId"), "SUPPORT_WORK".equals(entityType) ? entityId : null));
			case "S12" -> {
				put(p, "ledgerEntryId",
						firstNonNull(text(payload, "ledgerEntryId"), "LEDGER_ENTRY".equals(entityType) ? entityId : null));
				put(p, "taskId", firstNonNull(text(payload, "taskId"), "TASK".equals(entityType) ? entityId : null));
				put(p, "tab", text(payload, "tab"));
			}
			default -> {
			}
		}
		return new ScreenTarget(type.screen(), p);
	}

	@SafeVarargs
	private static <T> T firstNonNull(T... values) {
		for (T v : values) {
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	/** Danh sách tương ứng khi đích không còn dùng được ("về trang danh sách thích hợp"). */
	static ScreenTarget fallback(String screen, UUID projectId, boolean stillMember) {
		if (!stillMember) {
			return new ScreenTarget("S01", Map.of());
		}
		Map<String, String> g = Map.of("groupId", projectId.toString());
		return switch (screen) {
			case "S05" -> new ScreenTarget("S04", g);
			case "S09", "S10", "S11" -> new ScreenTarget("S08", g);
			case "S12" -> new ScreenTarget("S12", g);
			default -> new ScreenTarget("S03", g);
		};
	}

	/** Đích còn tồn tại và người nhận còn quyền xem (kiểm tra lại phía server khi mở). */
	boolean isAvailable(UUID userId, UUID projectId, String entityType, UUID entityId) {
		Optional<GroupMembership> membership = access.findActive(projectId, userId);
		if (membership.isEmpty()) {
			return false;
		}
		boolean lead = membership.get().isLeader();
		return switch (entityType) {
			case "GROUP" -> true;
			case "TASK" -> exists("SELECT 1 FROM tasks WHERE id = :id AND project_id = :p AND archived_at IS NULL", entityId,
					projectId, userId);
			case "PLAN_REVISION" -> exists("SELECT 1 FROM task_plan_revisions WHERE id = :id AND project_id = :p", entityId,
					projectId, userId);
			case "CHECKPOINT_CYCLE" -> exists(
					"SELECT 1 FROM checkpoint_cycles WHERE id = :id AND project_id = :p AND status <> 'CANCELLED'", entityId,
					projectId, userId);
			// Đề nghị hỗ trợ riêng: chỉ người gửi và TL (BR riêng tư)
			case "SUPPORT_REQUEST" -> lead
					? exists("SELECT 1 FROM support_requests WHERE id = :id AND project_id = :p", entityId, projectId, userId)
					: exists("SELECT 1 FROM support_requests WHERE id = :id AND project_id = :p AND requester_id = :u",
							entityId, projectId, userId);
			case "HELP_REQUEST" -> exists("SELECT 1 FROM help_requests WHERE id = :id AND project_id = :p", entityId,
					projectId, userId);
			case "SUPPORT_WORK" -> exists("SELECT 1 FROM support_works WHERE id = :id AND project_id = :p", entityId,
					projectId, userId);
			case "LEDGER_ENTRY" -> lead
					? exists("SELECT 1 FROM point_ledger_entries WHERE id = :id AND project_id = :p", entityId, projectId,
							userId)
					: exists("SELECT 1 FROM point_ledger_entries WHERE id = :id AND project_id = :p AND recipient_id = :u",
							entityId, projectId, userId);
			case "RISK_REPORT" -> exists("SELECT 1 FROM risk_reports WHERE id = :id AND project_id = :p", entityId,
					projectId, userId);
			case "POINT_POLICY" -> exists("SELECT 1 FROM point_policies WHERE id = :id AND project_id = :p", entityId,
					projectId, userId);
			default -> false;
		};
	}

	private boolean exists(String sql, UUID entityId, UUID projectId, UUID userId) {
		JdbcClient.StatementSpec spec = jdbc.sql(sql).param("id", entityId).param("p", projectId);
		if (sql.contains(":u")) {
			spec = spec.param("u", userId);
		}
		return !spec.query(Integer.class).list().isEmpty();
	}
}
