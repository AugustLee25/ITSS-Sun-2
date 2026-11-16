package app.hanare.notification;

/**
 * CỔNG CHUNG (do điều phối viên định nghĩa; BE-A cài đặt, mọi module khác gọi).
 * Không đổi chữ ký khi chưa báo điều phối viên.
 *
 * <p>Gọi TRONG cùng transaction với sự kiện nghiệp vụ. Idempotent theo {@link NotificationEvent#dedupKey()}:
 * gọi lặp/đồng thời không tạo bản thứ hai (ghi vào {@code notification_events} + {@code notifications}).
 */
public interface NotificationService {

	/** @return {@code true} nếu sự kiện mới được tạo; {@code false} nếu {@code dedupKey} đã tồn tại. */
	boolean publish(NotificationEvent event);
}
