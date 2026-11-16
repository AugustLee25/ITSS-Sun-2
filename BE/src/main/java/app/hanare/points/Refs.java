package app.hanare.points;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO tham chiếu dùng chung cho support/help/points (khớp UserRef/TaskRef/CycleRef/Page trong api-types.ts). */
public final class Refs {

	private Refs() {
	}

	public record UserRef(UUID userId, String name) {
	}

	public record TaskRef(UUID id, String title) {
	}

	public record CycleRef(UUID id, int sequenceNo, Instant periodStartAt, Instant closesAt, String label) {
	}

	public record ReasonRef(String code, String message) {
	}

	public record PageOf<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

		public static <T> PageOf<T> of(List<T> items, int page, int size, long total) {
			return new PageOf<>(items, page, size, total, size == 0 ? 0 : (int) ((total + size - 1) / size));
		}
	}

	/** Chuẩn hóa page/size theo §1.6: page ≥ 0 (mặc định 0), size 1..100 (mặc định 20). */
	public static int[] pageArgs(Integer page, Integer size) {
		int p = page == null ? 0 : page;
		int s = size == null ? 20 : size;
		if (p < 0) {
			throw ApiException.validation(List.of(new ApiError.FieldError("page", "page phải ≥ 0")));
		}
		if (s < 1 || s > 100) {
			throw ApiException.validation(List.of(new ApiError.FieldError("size", "size phải trong 1–100")));
		}
		return new int[] { p, s };
	}
}
