package app.hanare.points;

import app.hanare.common.ApiException;
import org.springframework.http.HttpStatus;

/** Tạo nhanh các mã lỗi nghiệp vụ (API_CONTRACT §9) cho support/help/points. */
public final class Err {

	private Err() {
	}

	public static ApiException conflict(String code, String message) {
		return ApiException.of(HttpStatus.CONFLICT, code, message);
	}

	public static ApiException unprocessable(String code, String message) {
		return ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
	}

	public static ApiException forbidden(String code, String message) {
		return ApiException.of(HttpStatus.FORBIDDEN, code, message);
	}

	public static ApiException badRequest(String code, String message) {
		return ApiException.of(HttpStatus.BAD_REQUEST, code, message);
	}
}
