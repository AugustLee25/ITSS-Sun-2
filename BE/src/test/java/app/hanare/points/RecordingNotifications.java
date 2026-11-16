package app.hanare.points;

import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * {@link NotificationService} GIẢ chỉ dùng trong test của BE-B2 (BE-A viết bản thật song song): ghi lại mọi sự kiện và
 * mô phỏng khử trùng theo {@code dedupKey}. Import bằng {@code @Import(RecordingNotifications.Config.class)}.
 */
public class RecordingNotifications implements NotificationService {

	private final List<NotificationEvent> events = new ArrayList<>();
	private final Set<String> dedup = new HashSet<>();

	@Override
	public synchronized boolean publish(NotificationEvent event) {
		if (event.dedupKey() == null || !dedup.add(event.dedupKey())) {
			return false;
		}
		events.add(event);
		return true;
	}

	public synchronized List<NotificationEvent> all() {
		return List.copyOf(events);
	}

	public synchronized List<NotificationEvent> ofType(String type) {
		return events.stream().filter(e -> type.equals(e.eventType())).toList();
	}

	public synchronized void clear() {
		events.clear();
		dedup.clear();
	}

	/** Toàn bộ nội dung hiển thị (tiêu đề + nội dung + payload) nối lại để dò rò rỉ chuỗi riêng tư. */
	public synchronized String allText() {
		StringBuilder sb = new StringBuilder();
		for (NotificationEvent e : events) {
			sb.append(e.title()).append(' ').append(e.message()).append(' ').append(e.payload()).append('\n');
		}
		return sb.toString();
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		RecordingNotifications recordingNotifications() {
			return new RecordingNotifications();
		}
	}
}
