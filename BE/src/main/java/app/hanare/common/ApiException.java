package app.hanare.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Ngoại lệ nghiệp vụ mang mã lỗi ổn định + HTTP status; {@link GlobalExceptionHandler} đổi thành {@link ApiError}.
 * <pre>
 * throw ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy task");          // mã chung
 * throw ApiException.of(HttpStatus.CONFLICT, "PLAN_CHANGED", "Kế hoạch đã đổi"); // mã nghiệp vụ theo API_CONTRACT
 * throw ApiException.validation("deadline", "Deadline phải nằm trong kỳ");
 * </pre>
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final List<ApiError.FieldError> fieldErrors;

	public ApiException(HttpStatus status, String code, String message, List<ApiError.FieldError> fieldErrors) {
		super(message);
		this.status = status;
		this.code = code;
		this.fieldErrors = fieldErrors != null ? List.copyOf(fieldErrors) : List.of();
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public List<ApiError.FieldError> fieldErrors() {
		return fieldErrors;
	}

	/** Mã chung; message null = dùng message mặc định của mã. */
	public static ApiException of(ErrorCode code, String message) {
		return new ApiException(code.status(), code.name(), message != null ? message : code.defaultMessage(), null);
	}

	/** Mã nghiệp vụ riêng (chuỗi trùng {@code ErrorCode} trong api-types.ts) với HTTP status theo contract. */
	public static ApiException of(HttpStatus status, String code, String message) {
		return new ApiException(status, code, message, null);
	}

	public static ApiException validation(String field, String message) {
		return validation(List.of(new ApiError.FieldError(field, message)));
	}

	public static ApiException validation(List<ApiError.FieldError> fieldErrors) {
		return new ApiException(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED.name(),
				ErrorCode.VALIDATION_FAILED.defaultMessage(), fieldErrors);
	}

	public static ApiException unauthenticated(String message) {
		return of(ErrorCode.UNAUTHENTICATED, message);
	}

	public static ApiException forbidden(String message) {
		return of(ErrorCode.FORBIDDEN, message);
	}

	public static ApiException notFound(String message) {
		return of(ErrorCode.NOT_FOUND, message);
	}

	public static ApiException versionConflict() {
		return of(ErrorCode.VERSION_CONFLICT, null);
	}

	public static ApiException invalidState(String message) {
		return of(ErrorCode.INVALID_STATE, message);
	}
}
