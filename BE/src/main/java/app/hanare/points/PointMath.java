package app.hanare.points;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Toán điểm thuần số nguyên (không dùng double): chia quỹ bằng phương pháp phần dư lớn nhất, định dạng nhãn. */
public final class PointMath {

	public static final long TOTAL_UNITS = 1_000_000L;
	public static final long UNITS_PER_PERCENT = 10_000L;

	private PointMath() {
	}

	/**
	 * Chia {@code total} units theo {@code weights} bằng phần dư lớn nhất: base_i = total*w_i / W, rem_i = total*w_i % W,
	 * phần còn lại chia mỗi unit cho phần tử có rem lớn nhất, hòa thì id nhỏ nhất. Σ kết quả == total.
	 *
	 * @throws IllegalArgumentException nếu W == 0, weights âm hoặc kích thước không khớp
	 */
	public static long[] allocate(long total, long[] weights, List<UUID> ids) {
		if (weights.length != ids.size()) {
			throw new IllegalArgumentException("weights/ids size mismatch");
		}
		if (total < 0) {
			throw new IllegalArgumentException("total < 0");
		}
		long sumW = 0;
		for (long w : weights) {
			if (w < 0) {
				throw new IllegalArgumentException("weight < 0");
			}
			sumW = Math.addExact(sumW, w);
		}
		if (sumW == 0) {
			throw new IllegalArgumentException("sum of weights == 0");
		}
		int n = weights.length;
		long[] base = new long[n];
		long[] rem = new long[n];
		long assigned = 0;
		for (int i = 0; i < n; i++) {
			long scaled = Math.multiplyExact(total, weights[i]);
			base[i] = scaled / sumW;
			rem[i] = scaled % sumW;
			assigned += base[i];
		}
		long left = total - assigned;
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			order.add(i);
		}
		order.sort(Comparator.<Integer>comparingLong(i -> -rem[i]).thenComparing(i -> ids.get(i).toString()));
		for (int k = 0; k < left; k++) {
			base[order.get(k)]++;
		}
		return base;
	}

	/** "10,0000" (không dấu, 4 chữ số thập phân, dấu phẩy). Âm -> "-…" . */
	public static String label(long units) {
		long abs = Math.abs(units);
		return (units < 0 ? "-" : "") + (abs / UNITS_PER_PERCENT) + "," + String.format("%04d", abs % UNITS_PER_PERCENT);
	}

	/** "+4,0000" | "-4,0000" | "0,0000". */
	public static String deltaLabel(long units) {
		if (units == 0) {
			return "0,0000";
		}
		return (units > 0 ? "+" : "-") + label(Math.abs(units));
	}

	/** confirmed / initial × 100 (4 chữ số thập phân), định dạng sẵn; null nếu không có initial. */
	public static String ratioLabel(long numeratorUnits, Long denominatorUnits) {
		if (denominatorUnits == null || denominatorUnits <= 0) {
			return null;
		}
		BigDecimal pct = BigDecimal.valueOf(numeratorUnits).multiply(BigDecimal.valueOf(100))
				.divide(BigDecimal.valueOf(denominatorUnits), 4, RoundingMode.HALF_UP);
		return pct.toPlainString().replace('.', ',');
	}

	public static int weightOf(String difficulty, int easy, int medium, int hard) {
		return switch (difficulty) {
			case "EASY" -> easy;
			case "MEDIUM" -> medium;
			case "HARD" -> hard;
			default -> throw new IllegalArgumentException("difficulty " + difficulty);
		};
	}

	public static String difficultyLabel(String difficulty) {
		return switch (difficulty) {
			case "EASY" -> "Dễ";
			case "MEDIUM" -> "Trung bình";
			case "HARD" -> "Khó";
			default -> difficulty;
		};
	}
}
