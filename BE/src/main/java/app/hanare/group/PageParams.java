package app.hanare.group;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import java.util.ArrayList;
import java.util.List;

/** Tham số phân trang chuẩn: {@code page} từ 0 (mặc định 0), {@code size} 1–100 (mặc định 20). */
public record PageParams(int page, int size) {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	public static PageParams of(Integer page, Integer size) {
		List<ApiError.FieldError> errors = new ArrayList<>();
		int p = page == null ? 0 : page;
		int s = size == null ? DEFAULT_SIZE : size;
		if (p < 0) {
			errors.add(new ApiError.FieldError("page", "Trang phải từ 0 trở lên."));
		}
		if (s < 1 || s > MAX_SIZE) {
			errors.add(new ApiError.FieldError("size", "Kích thước trang phải từ 1 đến " + MAX_SIZE + "."));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new PageParams(p, s);
	}

	public long offset() {
		return (long) page * size;
	}
}
