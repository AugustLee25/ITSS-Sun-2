package app.hanare.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Đồng hồ điều khiển được của profile demo: chưa đặt thì chạy theo giờ hệ thống, đã đặt thì đứng yên tại giờ đó. */
public class DemoClock extends Clock {

	private volatile Instant fixed;

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		Instant f = fixed;
		return f != null ? f : Instant.now();
	}

	public boolean isFixed() {
		return fixed != null;
	}

	public void set(Instant now) {
		this.fixed = now;
	}
}
