package app.hanare.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;

/** Nhãn tiếng Việt do server dựng (kỳ, lịch, pha). */
public final class ScheduleLabels {

	private static final String[] WEEKDAYS = { "thứ Hai", "thứ Ba", "thứ Tư", "thứ Năm", "thứ Sáu", "thứ Bảy", "Chủ nhật" };
	private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private ScheduleLabels() {
	}

	/** Giá trị Instant trả ra API: cắt về mili-giây (hợp đồng: phần lẻ giây ≤ 3 chữ số). */
	public static Instant ms(Instant instant) {
		return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
	}

	/** "thứ Sáu" / "Chủ nhật" (viết thường, dùng giữa câu). */
	public static String weekdayName(int isoWeekday) {
		return WEEKDAYS[isoWeekday - 1];
	}

	/** "Thứ Sáu" / "Chủ nhật" (viết hoa chữ đầu). */
	public static String weekdayTitle(int isoWeekday) {
		String name = weekdayName(isoWeekday);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	/** "Kỳ 3 · 13/11 22:00 → 20/11 22:00" theo múi giờ của kỳ. */
	public static String cycleLabel(int sequenceNo, Instant periodStart, Instant closes, ZoneId zone) {
		return "Kỳ " + sequenceNo + " · " + periodStart.atZone(zone).format(DAY_TIME) + " → "
				+ closes.atZone(zone).format(DAY_TIME);
	}

	/** "Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00" (giờ nhắc = giờ chốt kế tiếp trừ số phút nhắc trước). */
	public static String summary(ScheduleSpec spec, Instant reference) {
		Instant cutoff = ScheduleCalculator.firstCutoffAfter(spec, reference);
		ZonedDateTime cut = cutoff.atZone(spec.zone());
		ZonedDateTime rem = cutoff.minus(Duration.ofMinutes(spec.reminderMinutes())).atZone(spec.zone());
		return "Chốt " + weekdayName(cut.getDayOfWeek().getValue()) + " " + cut.format(TIME) + " → nhắc "
				+ weekdayName(rem.getDayOfWeek().getValue()) + " " + rem.format(TIME);
	}

	/** "22:00 Thứ Sáu 20/11" — dùng trong nội dung thông báo. */
	public static String cutoffMoment(Instant closes, ZoneId zone) {
		ZonedDateTime z = closes.atZone(zone);
		return z.format(TIME) + " " + weekdayTitle(z.getDayOfWeek().getValue()) + " " + z.format(DAY);
	}

	public static String phaseLabel(String status) {
		return switch (status) {
			case "CLOSED" -> "Chờ TL kiểm tra";
			case "FINALIZED" -> "Đã chốt đánh giá";
			default -> "Tạm tính";
		};
	}

	public static String phase(String status) {
		return switch (status) {
			case "CLOSED" -> "AWAITING_LEAD_REVIEW";
			case "FINALIZED" -> "FINALIZED";
			default -> "TENTATIVE";
		};
	}

	/** "7 giờ" / "7 giờ 30 phút". */
	public static String durationVi(long minutes) {
		long h = minutes / 60;
		long m = minutes % 60;
		if (h == 0) {
			return m + " phút";
		}
		return m == 0 ? h + " giờ" : h + " giờ " + m + " phút";
	}
}
