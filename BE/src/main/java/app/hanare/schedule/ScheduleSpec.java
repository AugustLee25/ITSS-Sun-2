package app.hanare.schedule;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Lịch checkpoint đã kiểm tra hợp lệ (giờ địa phương). {@code windowMinutes == null}: mở báo cáo cả kỳ.
 */
public record ScheduleSpec(int weekday, LocalTime cutoff, ZoneId zone, Integer windowMinutes, int reminderMinutes) {

	/** Nhắc trước giờ chốt 7 giờ (spec 0.5). */
	public static final int DEFAULT_REMINDER_MINUTES = 420;
}
