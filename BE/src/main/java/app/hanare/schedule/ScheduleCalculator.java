package app.hanare.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Sinh các mốc UTC của kỳ checkpoint từ lịch giờ địa phương (server là nơi DUY NHẤT tính giờ; xử lý DST).
 * Kỳ = {@code (periodStartAt, closesAt]}; {@code reminderAt = closesAt - reminderMinutes};
 * {@code opensAt = periodStartAt} nếu không có khung mở, ngược lại {@code max(periodStartAt, closesAt - window)}.
 */
public final class ScheduleCalculator {

	/** Một kỳ chưa gắn id/số thứ tự. */
	public record CyclePlan(Instant periodStartAt, Instant opensAt, Instant reminderAt, Instant closesAt) {
	}

	private ScheduleCalculator() {
	}

	/** Giờ chốt ĐẦU TIÊN lớn hơn hẳn {@code after} (deadline đúng giờ chốt thuộc kỳ vừa kết thúc, không thuộc kỳ sau). */
	public static Instant firstCutoffAfter(ScheduleSpec spec, Instant after) {
		LocalDate day = after.atZone(spec.zone()).toLocalDate().minusDays(1);
		for (int i = 0; i < 16; i++) {
			LocalDate date = day.plusDays(i);
			if (date.getDayOfWeek().getValue() != spec.weekday()) {
				continue;
			}
			// ZonedDateTime.of xử lý giờ rơi vào khoảng trống DST (đẩy lùi vào sau) / trùng lặp (lấy offset sớm hơn)
			Instant candidate = ZonedDateTime.of(date, spec.cutoff(), spec.zone()).toInstant();
			if (candidate.isAfter(after)) {
				return candidate;
			}
		}
		throw new IllegalStateException("Không tìm được giờ chốt kế tiếp");
	}

	public static CyclePlan plan(ScheduleSpec spec, Instant periodStart, Instant closes) {
		Instant opens = periodStart;
		if (spec.windowMinutes() != null) {
			Instant windowStart = closes.minus(Duration.ofMinutes(spec.windowMinutes()));
			opens = windowStart.isAfter(periodStart) ? windowStart : periodStart;
		}
		return new CyclePlan(periodStart, opens, closes.minus(Duration.ofMinutes(spec.reminderMinutes())), closes);
	}

	/**
	 * {@code n} kỳ liên tiếp bắt đầu từ {@code anchorStart}. Kỳ đầu đóng ở giờ chốt đầu tiên sau
	 * {@code max(anchorStart, notBefore)} (dùng {@code notBefore = now} để kỳ đầu của nhóm mới luôn chưa kết thúc).
	 */
	public static List<CyclePlan> chain(ScheduleSpec spec, Instant anchorStart, Instant notBefore, int n) {
		List<CyclePlan> plans = new ArrayList<>(n);
		Instant start = anchorStart;
		Instant floor = notBefore != null && notBefore.isAfter(anchorStart) ? notBefore : anchorStart;
		for (int i = 0; i < n; i++) {
			Instant closes = firstCutoffAfter(spec, i == 0 ? floor : start);
			plans.add(plan(spec, start, closes));
			start = closes;
		}
		return plans;
	}

	/** Kỳ "đều" = đúng 7 ngày theo giờ địa phương (tuần có DST vẫn đều dù dài 167/169 giờ). */
	public static boolean isRegular(ScheduleSpec spec, CyclePlan plan) {
		ZonedDateTime start = plan.periodStartAt().atZone(spec.zone());
		ZonedDateTime end = plan.closesAt().atZone(spec.zone());
		return end.toLocalDateTime().equals(start.toLocalDateTime().plusDays(7));
	}

	public static long periodHours(CyclePlan plan) {
		return Math.round(Duration.between(plan.periodStartAt(), plan.closesAt()).toMinutes() / 60.0);
	}
}
