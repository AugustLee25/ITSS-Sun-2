package app.hanare.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.schedule.ScheduleCalculator.CyclePlan;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tính giờ chốt/nhắc/kỳ thuần (không Spring): ví dụ spec 0.5, 12.10 và DST. */
class ScheduleCalculatorTest {

	private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

	private static ScheduleSpec spec(int weekday, String time, ZoneId zone, Integer window) {
		return new ScheduleSpec(weekday, LocalTime.parse(time), zone, window, ScheduleSpec.DEFAULT_REMINDER_MINUTES);
	}

	@Test
	void fridayCutoff22VnGivesReminderAt15ZeroSameDay() {
		ScheduleSpec s = spec(5, "22:00", VN, null);
		// kỳ 13/11 22:00 -> 20/11 22:00 giờ VN (ví dụ hợp đồng §1.4)
		CyclePlan p = ScheduleCalculator.chain(s, Instant.parse("2026-11-13T15:00:00Z"), null, 1).get(0);
		assertThat(p.periodStartAt()).isEqualTo(Instant.parse("2026-11-13T15:00:00Z"));
		assertThat(p.closesAt()).isEqualTo(Instant.parse("2026-11-20T15:00:00Z"));
		assertThat(p.reminderAt()).isEqualTo(Instant.parse("2026-11-20T08:00:00Z")); // 15:00 VN cùng ngày
		assertThat(p.opensAt()).isEqualTo(p.periodStartAt()); // không có khung mở: mở cả kỳ
	}

	@Test
	void sundayCutoff20VnGivesReminderAt13Sunday() {
		ScheduleSpec s = spec(7, "20:00", VN, null);
		Instant closes = ScheduleCalculator.firstCutoffAfter(s, Instant.parse("2026-11-16T03:00:00Z"));
		assertThat(closes).isEqualTo(Instant.parse("2026-11-22T13:00:00Z")); // CN 22/11 20:00 VN
		CyclePlan p = ScheduleCalculator.plan(s, Instant.parse("2026-11-15T17:00:00Z"), closes);
		assertThat(p.reminderAt()).isEqualTo(Instant.parse("2026-11-22T06:00:00Z")); // CN 13:00 VN
		assertThat(ScheduleLabels.summary(s, Instant.parse("2026-11-16T03:00:00Z")))
				.isEqualTo("Chốt Chủ nhật 20:00 → nhắc Chủ nhật 13:00");
	}

	@Test
	void summaryLabelMatchesSpecExample() {
		assertThat(ScheduleLabels.summary(spec(5, "22:00", VN, null), Instant.parse("2026-11-16T03:00:00Z")))
				.isEqualTo("Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00");
		// nhắc có thể rơi sang ngày hôm trước
		assertThat(ScheduleLabels.summary(spec(1, "03:00", VN, null), Instant.parse("2026-11-16T03:00:00Z")))
				.isEqualTo("Chốt thứ Hai 03:00 → nhắc Chủ nhật 20:00");
	}

	@Test
	void cutoffInstantItselfBelongsToThatCycleAndNextOneStartsAfter() {
		ScheduleSpec s = spec(5, "22:00", VN, null);
		Instant cutoff = Instant.parse("2026-11-20T15:00:00Z");
		// "sau" đúng giờ chốt -> giờ chốt tuần kế tiếp (khoảng trái mở: kỳ mới bắt đầu SAU giờ chốt)
		assertThat(ScheduleCalculator.firstCutoffAfter(s, cutoff)).isEqualTo(Instant.parse("2026-11-27T15:00:00Z"));
		assertThat(ScheduleCalculator.firstCutoffAfter(s, cutoff.minusMillis(1))).isEqualTo(cutoff);
		assertThat(ScheduleCalculator.firstCutoffAfter(s, cutoff.plusMillis(1)))
				.isEqualTo(Instant.parse("2026-11-27T15:00:00Z"));
	}

	@Test
	void chainIsContiguousAndWeekly() {
		List<CyclePlan> plans = ScheduleCalculator.chain(spec(5, "22:00", VN, null), Instant.parse("2026-11-13T15:00:00Z"),
				null, 5);
		for (int i = 1; i < plans.size(); i++) {
			assertThat(plans.get(i).periodStartAt()).isEqualTo(plans.get(i - 1).closesAt());
			assertThat(Duration.between(plans.get(i).periodStartAt(), plans.get(i).closesAt())).isEqualTo(Duration.ofDays(7));
		}
	}

	@Test
	void reportingWindowNarrowsOpensAtButNeverBeforePeriodStart() {
		ScheduleSpec s = spec(5, "22:00", VN, 24 * 60);
		CyclePlan p = ScheduleCalculator.chain(s, Instant.parse("2026-11-13T15:00:00Z"), null, 1).get(0);
		assertThat(p.opensAt()).isEqualTo(Instant.parse("2026-11-19T15:00:00Z")); // closes - 24h
		ScheduleSpec huge = spec(5, "22:00", VN, 20160);
		assertThat(ScheduleCalculator.chain(huge, Instant.parse("2026-11-13T15:00:00Z"), null, 1).get(0).opensAt())
				.isEqualTo(Instant.parse("2026-11-13T15:00:00Z"));
	}

	@Test
	void firstCycleOfNewGroupEndsAfterNowEvenWhenTodayCutoffAlreadyPassed() {
		ScheduleSpec s = spec(5, "22:00", VN, null);
		Instant start = Instant.parse("2026-11-19T17:00:00Z"); // 00:00 thứ Sáu 20/11 giờ VN
		Instant fridayNight23 = Instant.parse("2026-11-20T16:00:00Z"); // 23:00 VN, đã qua 22:00
		CyclePlan first = ScheduleCalculator.chain(s, start, fridayNight23, 1).get(0);
		assertThat(first.periodStartAt()).isEqualTo(start);
		assertThat(first.closesAt()).isEqualTo(Instant.parse("2026-11-27T15:00:00Z"));
	}

	@Test
	void dstWeekKeepsLocalCutoffAndStaysRegular() {
		// Mỹ hết DST 01/11/2026 02:00: Chủ nhật 22:00 EDT (UTC-4) -> EST (UTC-5)
		ZoneId ny = ZoneId.of("America/New_York");
		ScheduleSpec s = spec(7, "22:00", ny, null);
		Instant anchor = Instant.parse("2026-10-26T02:00:00Z"); // CN 25/10 22:00 EDT
		List<CyclePlan> plans = ScheduleCalculator.chain(s, anchor, null, 2);
		assertThat(plans.get(0).closesAt()).isEqualTo(Instant.parse("2026-11-02T03:00:00Z")); // CN 01/11 22:00 EST
		assertThat(ScheduleCalculator.periodHours(plans.get(0))).isEqualTo(169);
		assertThat(ScheduleCalculator.isRegular(s, plans.get(0))).isTrue(); // vẫn đúng 7 ngày địa phương
		assertThat(plans.get(1).closesAt()).isEqualTo(Instant.parse("2026-11-09T03:00:00Z"));
	}

	@Test
	void cutoffInSpringForwardGapMovesToAfterTheGap() {
		ZoneId ny = ZoneId.of("America/New_York");
		ScheduleSpec s = spec(7, "02:30", ny, null); // 08/03/2026 02:30 không tồn tại
		Instant closes = ScheduleCalculator.firstCutoffAfter(s, Instant.parse("2026-03-05T00:00:00Z"));
		assertThat(closes).isEqualTo(Instant.parse("2026-03-08T07:30:00Z")); // 03:30 EDT
	}

	@Test
	void changingWeekdayMakesAShortTransitionCycle() {
		ScheduleSpec sunday = spec(7, "20:00", VN, null);
		CyclePlan transition = ScheduleCalculator.chain(sunday, Instant.parse("2026-11-20T15:00:00Z"), null, 1).get(0);
		assertThat(ScheduleCalculator.periodHours(transition)).isEqualTo(46);
		assertThat(ScheduleCalculator.isRegular(sunday, transition)).isFalse();
	}
}
