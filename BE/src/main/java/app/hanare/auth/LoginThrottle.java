package app.hanare.auth;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Giới hạn số lần đăng nhập SAI (chống dò mật khẩu), lưu trong bộ nhớ:
 * <ul>
 * <li>tối đa {@value #MAX_FAILURES_PER_ACCOUNT} lần sai / {@value #WINDOW_SECONDS}s cho mỗi cặp (địa chỉ, email);</li>
 * <li>tối đa {@value #MAX_FAILURES_PER_ADDRESS} lần sai / {@value #WINDOW_SECONDS}s cho mỗi địa chỉ (dò nhiều email).</li>
 * </ul>
 * Vượt ngưỡng → {@code 429 RATE_LIMITED}, kể cả khi mật khẩu gửi lên đúng, cho tới khi cửa sổ trôi qua. Đăng nhập đúng xóa bộ
 * đếm của cặp (địa chỉ, email). Dùng đồng hồ đơn điệu thật ({@code System.nanoTime}) chứ KHÔNG dùng bean {@code Clock}, vì
 * đồng hồ demo đứng yên/tua tay sẽ làm khóa không bao giờ hết hạn. Email là khóa đã chuẩn hóa nên không phân biệt hoa/thường.
 */
@Component
class LoginThrottle {

	static final int MAX_FAILURES_PER_ACCOUNT = 5;
	static final int MAX_FAILURES_PER_ADDRESS = 30;
	static final long WINDOW_SECONDS = 300;
	/** Chặn bộ nhớ phình khi bị dò hàng loạt email: quá ngưỡng thì dọn các khóa đã hết hạn. */
	private static final int PURGE_THRESHOLD = 10_000;

	private static final long WINDOW_NANOS = WINDOW_SECONDS * 1_000_000_000L;

	private final LongSupplier nanoTime;
	private final Map<String, Deque<Long>> failures = new HashMap<>();

	@Autowired
	LoginThrottle() {
		this(System::nanoTime);
	}

	LoginThrottle(LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
	}

	private static String accountKey(String address, String email) {
		return "a|" + address + "|" + email;
	}

	private static String addressKey(String address) {
		return "i|" + address;
	}

	/** Gọi TRƯỚC khi kiểm tra mật khẩu. */
	synchronized void check(String address, String email) {
		long now = nanoTime.getAsLong();
		long wait = Math.max(secondsUntilFree(accountKey(address, email), MAX_FAILURES_PER_ACCOUNT, now),
				secondsUntilFree(addressKey(address), MAX_FAILURES_PER_ADDRESS, now));
		if (wait > 0) {
			throw ApiException.of(ErrorCode.RATE_LIMITED,
					"Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau " + wait + " giây.");
		}
	}

	synchronized void recordFailure(String address, String email) {
		long now = nanoTime.getAsLong();
		if (failures.size() > PURGE_THRESHOLD) {
			failures.values().removeIf(d -> prune(d, now).isEmpty());
		}
		failures.computeIfAbsent(accountKey(address, email), k -> new ArrayDeque<>()).addLast(now);
		failures.computeIfAbsent(addressKey(address), k -> new ArrayDeque<>()).addLast(now);
	}

	synchronized void reset(String address, String email) {
		failures.remove(accountKey(address, email));
	}

	private long secondsUntilFree(String key, int max, long now) {
		Deque<Long> times = failures.get(key);
		if (times == null) {
			return 0;
		}
		prune(times, now);
		if (times.size() < max) {
			return 0;
		}
		// khóa tới khi lần sai CŨ NHẤT trong cửa sổ trôi ra ngoài
		long freeAt = times.peekFirst() + WINDOW_NANOS;
		return Math.max(1, (freeAt - now + 999_999_999L) / 1_000_000_000L);
	}

	private static Deque<Long> prune(Deque<Long> times, long now) {
		while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_NANOS) {
			times.pollFirst();
		}
		return times;
	}
}
