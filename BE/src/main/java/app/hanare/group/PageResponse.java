package app.hanare.group;

import java.util.List;

/** Trang theo offset: {@code {items,page,size,totalItems,totalPages}}; {@code page} bắt đầu từ 0. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	public static <T> PageResponse<T> of(List<T> items, PageParams params, long totalItems) {
		int totalPages = (int) ((totalItems + params.size() - 1) / params.size());
		return new PageResponse<>(items, params.page(), params.size(), totalItems, totalPages);
	}
}
