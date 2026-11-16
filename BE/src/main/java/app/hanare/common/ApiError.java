package app.hanare.common;

import java.util.List;

/**
 * Dạng lỗi chung của mọi API: {@code {"code","message","fieldErrors":[{"field","message"}],"traceId"}}.
 * {@code fieldErrors} luôn có mặt (mảng rỗng nếu không có).
 */
public record ApiError(String code, String message, List<FieldError> fieldErrors, String traceId) {

	public record FieldError(String field, String message) {
	}

	public static ApiError of(ErrorCode code, String message, List<FieldError> fieldErrors) {
		return of(code.name(), message != null ? message : code.defaultMessage(), fieldErrors);
	}

	public static ApiError of(String code, String message, List<FieldError> fieldErrors) {
		return new ApiError(code, message, fieldErrors != null ? List.copyOf(fieldErrors) : List.of(),
				TraceIdFilter.currentTraceId());
	}
}
