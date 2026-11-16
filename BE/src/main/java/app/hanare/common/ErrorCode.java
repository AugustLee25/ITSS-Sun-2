package app.hanare.common;

import org.springframework.http.HttpStatus;

/**
 * Mã lỗi CHUNG của hạ tầng, tên khớp {@code ErrorCode} trong docs/api-types.ts (FE chỉ dựa vào mã, không dựa message).
 * Mã nghiệp vụ riêng (vd. PLAN_CHANGED, DEADLINE_OUTSIDE_CYCLE...) không nằm ở đây:
 * ném bằng {@link ApiException#of(HttpStatus, String, String)} với đúng chuỗi mã + HTTP status theo docs/API_CONTRACT.md.
 */
public enum ErrorCode {
	/** Dữ liệu vào sai (kèm fieldErrors). */
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ."),
	/** Request sai cấu trúc (JSON hỏng, thiếu tham số...). */
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Yêu cầu không hợp lệ."),
	/** Thiếu hoặc sai token. */
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập."),
	/** Token đã hết hạn (FE đưa người dùng về đăng nhập). */
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng."),
	/** Đã đăng nhập nhưng không đủ quyền. */
	FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này."),
	/** Không phải thành viên đang hoạt động của nhóm. Trả 404 (cùng dạng với nhóm không tồn tại) để không lộ nhóm. */
	NOT_A_GROUP_MEMBER(HttpStatus.NOT_FOUND, "Không tìm thấy nhóm hoặc bạn không thuộc nhóm này."),
	/** Là thành viên nhưng thao tác chỉ dành cho trưởng nhóm. */
	LEAD_ONLY(HttpStatus.FORBIDDEN, "Chỉ trưởng nhóm được thực hiện thao tác này."),
	NOT_ASSIGNEE(HttpStatus.FORBIDDEN, "Bạn không phải người phụ trách task này."),
	NOT_AUTHORIZED_REVIEWER(HttpStatus.FORBIDDEN, "Bạn không phải người duyệt được chỉ định."),
	SELF_ACTION_FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không thể tự thực hiện thao tác này cho chính mình."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu."),
	/** Sai version khi cập nhật (người khác vừa sửa) — UI tải lại bản mới. Cũng dùng cho vi phạm unique/FK do đua ghi. */
	VERSION_CONFLICT(HttpStatus.CONFLICT, "Nội dung vừa được người khác thay đổi."),
	/** Trạng thái hiện tại không cho phép thao tác. */
	INVALID_STATE(HttpStatus.CONFLICT, "Trạng thái hiện tại không cho phép thao tác này."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Bạn thao tác quá nhanh. Vui lòng thử lại sau."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi hệ thống. Vui lòng thử lại sau."),
	// Hạ tầng HTTP (không có trong api-types.ts; chỉ do framework sinh ra)
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức không được hỗ trợ."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng nội dung không được hỗ trợ.");

	private final HttpStatus status;
	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return status;
	}

	public String defaultMessage() {
		return defaultMessage;
	}
}
