package app.hanare.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Toán điểm thuần: phần dư lớn nhất, bất biến Σ = quỹ, nhãn hiển thị (RESEARCH mục D, spec 12.8.2). */
class PointMathTest {

	private static List<UUID> ids(int n) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			ids.add(UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", i + 1)));
		}
		return ids;
	}

	@Test
	void basketOneTwoThreeOfTwentyPercentGivesSpecExample() {
		long[] p = PointMath.allocate(200_000, new long[] { 1, 2, 3 }, ids(3));
		assertThat(p).containsExactly(33_333, 66_667, 100_000);
		assertThat(PointMath.label(p[0])).isEqualTo("3,3333");
		assertThat(PointMath.label(p[1])).isEqualTo("6,6667");
		assertThat(PointMath.label(p[2])).isEqualTo("10,0000");
	}

	@Test
	void threeEqualTasksDoNotExceedQuotaWhenRoundedTogether() {
		long[] p = PointMath.allocate(200_000, new long[] { 1, 1, 1 }, ids(3));
		assertThat(p).containsExactly(66_667, 66_667, 66_666);
		assertThat(p[0] + p[1] + p[2]).isEqualTo(200_000);
	}

	@Test
	void fiveHardTasksOfOneMemberAreFourPercentEach() {
		long[] p = PointMath.allocate(200_000, new long[] { 3, 3, 3, 3, 3 }, ids(5));
		assertThat(p).containsOnly(40_000);
	}

	@Test
	void memberQuotaForGroupsNotDivisibleByNTieBreaksByStableId() {
		long[] p = PointMath.allocate(PointMath.TOTAL_UNITS, new long[] { 1, 1, 1 }, ids(3));
		assertThat(p).containsExactly(333_334, 333_333, 333_333);
		long[] seven = PointMath.allocate(PointMath.TOTAL_UNITS, new long[] { 1, 1, 1, 1, 1, 1, 1 }, ids(7));
		assertThat(java.util.Arrays.stream(seven).sum()).isEqualTo(PointMath.TOTAL_UNITS);
		assertThat(java.util.Arrays.stream(seven).max().getAsLong() - java.util.Arrays.stream(seven).min().getAsLong())
				.isLessThanOrEqualTo(1);
	}

	@Test
	void sumIsAlwaysExactlyTheQuotaForRandomWeightsAndSizes() {
		Random rnd = new Random(42);
		for (int round = 0; round < 500; round++) {
			int n = 1 + rnd.nextInt(12);
			long[] w = new long[n];
			for (int i = 0; i < n; i++) {
				w[i] = 1 + rnd.nextInt(3);
			}
			long total = 1 + rnd.nextInt(1_000_000);
			long[] p = PointMath.allocate(total, w, ids(n));
			assertThat(java.util.Arrays.stream(p).sum()).as("n=%d total=%d", n, total).isEqualTo(total);
			// mỗi phần chỉ lệch tối đa 1 unit so với chia chính xác
			long sumW = java.util.Arrays.stream(w).sum();
			for (int i = 0; i < n; i++) {
				long floor = total * w[i] / sumW;
				assertThat(p[i]).isBetween(floor, floor + 1);
			}
		}
	}

	@Test
	void zeroWeightSumIsRejectedAndLabelsAreFormattedByServer() {
		assertThatThrownBy(() -> PointMath.allocate(100, new long[] { 0, 0 }, ids(2))).isInstanceOf(IllegalArgumentException.class);
		assertThat(PointMath.label(0)).isEqualTo("0,0000");
		assertThat(PointMath.label(1_000_000)).isEqualTo("100,0000");
		assertThat(PointMath.label(1)).isEqualTo("0,0001");
		assertThat(PointMath.deltaLabel(40_000)).isEqualTo("+4,0000");
		assertThat(PointMath.deltaLabel(-40_000)).isEqualTo("-4,0000");
		assertThat(PointMath.deltaLabel(0)).isEqualTo("0,0000");
		assertThat(PointMath.ratioLabel(100_000, 200_000L)).isEqualTo("50,0000");
		assertThat(PointMath.ratioLabel(1, null)).isNull();
		assertThat(PointAmount.of(100_000).percentLabel()).isEqualTo("10,0000");
	}
}
