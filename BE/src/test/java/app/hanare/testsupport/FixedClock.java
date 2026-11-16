package app.hanare.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Đồng hồ test có thể đặt/tua: {@code clock.set(Instant.parse("2026-11-20T15:00:00Z"))}. Luôn UTC. */
public class FixedClock extends Clock {

	private volatile Instant now;

	public FixedClock(Instant initial) {
		this.now = initial;
	}

	public void set(Instant instant) {
		this.now = instant;
	}

	public void advance(Duration duration) {
		this.now = this.now.plus(duration);
	}

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
		return now;
	}
}
