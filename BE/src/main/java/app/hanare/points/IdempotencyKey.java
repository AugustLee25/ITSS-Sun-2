package app.hanare.points;

import app.hanare.common.ApiException;

/**
 * Kiểm tra header {@code Idempotency-Key} cho endpoint REQUIRED (§1.9). Lặp lại thao tác an toàn nhờ idempotent tự nhiên
 * (kiểm tra trạng thái trong transaction + unique ràng buộc DB); lưu/phát lại response theo khóa là việc của bộ lọc dùng chung.
 */
public final class IdempotencyKey {

	private IdempotencyKey() {
	}

	public static String require(String header) {
		if (header == null || header.isBlank()) {
			throw Err.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key cho thao tác này.");
		}
		String key = header.trim();
		if (key.length() < 8 || key.length() > 64) {
			throw ApiException.validation("Idempotency-Key", "Khóa phải dài 8–64 ký tự.");
		}
		return key;
	}
}
