package app.hanare.points;

/**
 * Một lượng điểm (spec 12.8): {@code pointUnits} nguyên (1.000.000 = 100%, 1 điểm % = 10.000 units) và
 * {@code percentLabel} server định dạng sẵn ("10,0000"; biến động có dấu "+4,0000"/"-4,0000"/"0,0000").
 */
public record PointAmount(long pointUnits, String percentLabel) {

	public static PointAmount of(long units) {
		return new PointAmount(units, PointMath.label(units));
	}

	/** Biến động có dấu: {@code percentLabel} luôn kèm "+" hoặc "-" (trừ 0). */
	public static PointAmount delta(long units) {
		return new PointAmount(units, PointMath.deltaLabel(units));
	}

	public static PointAmount ofOrNull(Long units) {
		return units == null ? null : of(units);
	}
}
