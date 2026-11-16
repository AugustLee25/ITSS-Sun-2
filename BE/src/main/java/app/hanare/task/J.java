package app.hanare.task;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tiện ích dựng JSON phản hồi (map có thứ tự, cho phép null), thời gian mili-giây, định dạng điểm, kiểm tra đầu vào. */
public final class J {

	private J() {
	}

	/** {@code o("k1", v1, "k2", v2, ...)} -> LinkedHashMap giữ thứ tự, cho phép null. */
	public static Map<String, Object> o(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1] instanceof Instant t ? t(t) : kv[i + 1]);
		}
		return m;
	}

	public static String t(Instant i) {
		return i == null ? null : i.truncatedTo(ChronoUnit.MILLIS).toString();
	}

	public static Instant now(Clock clock) {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	public static Instant ms(Instant i) {
		return i == null ? null : i.truncatedTo(ChronoUnit.MILLIS);
	}

	public static Instant inst(ResultSet rs, String col) throws SQLException {
		OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
		return v == null ? null : v.toInstant().truncatedTo(ChronoUnit.MILLIS);
	}

	public static UUID uuid(ResultSet rs, String col) throws SQLException {
		return rs.getObject(col, UUID.class);
	}

	// ------------------------------------------------------------------ điểm (spec 12.8: 1 đơn vị = 0,0001 điểm %)
	public static String percentLabel(long units) {
		long abs = Math.abs(units);
		String s = (abs / 10_000) + "," + String.format("%04d", abs % 10_000);
		return units < 0 ? "-" + s : s;
	}

	public static Map<String, Object> amount(long units) {
		return o("pointUnits", units, "percentLabel", percentLabel(units));
	}

	public static Map<String, Object> delta(long units) {
		String s = percentLabel(units);
		return o("pointUnits", units, "percentLabel", units > 0 ? "+" + s : s);
	}

	// ------------------------------------------------------------------ kiểm tra đầu vào
	/** Gom lỗi trường rồi ném VALIDATION_FAILED một lần. */
	public static final class Check {
		private final List<ApiError.FieldError> errors = new ArrayList<>();

		public Check add(String field, String message) {
			errors.add(new ApiError.FieldError(field, message));
			return this;
		}

		public Check require(boolean ok, String field, String message) {
			if (!ok) {
				add(field, message);
			}
			return this;
		}

		public Check len(String value, String field, int min, int max) {
			int n = value == null ? 0 : value.strip().length();
			if (n < min || n > max) {
				add(field, "Độ dài phải từ " + min + " đến " + max + " ký tự");
			}
			return this;
		}

		public void done() {
			if (!errors.isEmpty()) {
				throw ApiException.validation(errors);
			}
		}
	}

	public static Check check() {
		return new Check();
	}
}
