package app.hanare.common;

import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Đổi mọi ngoại lệ thành {@link ApiError} với mã lỗi ổn định + traceId. */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApi(ApiException ex) {
		return ResponseEntity.status(ex.status()).body(ApiError.of(ex.code(), ex.getMessage(), ex.fieldErrors()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex) {
		List<ApiError.FieldError> fields = new ArrayList<>();
		for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
			fields.add(new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()));
		}
		for (ObjectError oe : ex.getBindingResult().getGlobalErrors()) {
			fields.add(new ApiError.FieldError(oe.getObjectName(), oe.getDefaultMessage()));
		}
		return respond(ErrorCode.VALIDATION_FAILED, null, fields);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex) {
		List<ApiError.FieldError> fields = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			String param = result.getMethodParameter().getParameterName();
			result.getResolvableErrors().forEach(err -> fields.add(new ApiError.FieldError(
					err instanceof FieldError fe ? fe.getField() : param, err.getDefaultMessage())));
		});
		return respond(ErrorCode.VALIDATION_FAILED, null, fields);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
		List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
				.map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
		return respond(ErrorCode.VALIDATION_FAILED, null, fields);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return respond(ErrorCode.VALIDATION_FAILED, null,
				List.of(new ApiError.FieldError(ex.getName(), "Giá trị không hợp lệ.")));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
		return respond(ErrorCode.VALIDATION_FAILED, null,
				List.of(new ApiError.FieldError(ex.getParameterName(), "Thiếu tham số bắt buộc.")));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
		return respond(ErrorCode.MALFORMED_REQUEST, "Nội dung yêu cầu không đọc được (JSON sai định dạng hoặc sai kiểu).", null);
	}

	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	ResponseEntity<ApiError> handleNoHandler(Exception ex) {
		return respond(ErrorCode.NOT_FOUND, null, null);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		return respond(ErrorCode.METHOD_NOT_ALLOWED, null, null);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
		return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, null);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
		return respond(ErrorCode.FORBIDDEN, null, null);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
		return respond(ErrorCode.UNAUTHENTICATED, null, null);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
		return respond(ErrorCode.VERSION_CONFLICT, null, null);
	}

	/**
	 * Vi phạm unique/FK/CHECK/trigger của DB (kể cả DuplicateKeyException) -> 409 VERSION_CONFLICT (thường do đua ghi),
	 * không lộ chi tiết SQL. Service phải tự kiểm tra trước để trả mã nghiệp vụ cụ thể; đây chỉ là lưới an toàn.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation, traceId={}: {}", TraceIdFilter.currentTraceId(),
				ex.getMostSpecificCause().getMessage());
		return respond(ErrorCode.VERSION_CONFLICT, null, null);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		log.error("Unhandled exception, traceId={}", TraceIdFilter.currentTraceId(), ex);
		return respond(ErrorCode.INTERNAL_ERROR, null, null);
	}

	private static ResponseEntity<ApiError> respond(ErrorCode code, String message, List<ApiError.FieldError> fields) {
		return ResponseEntity.status(code.status()).body(ApiError.of(code, message, fields));
	}
}
