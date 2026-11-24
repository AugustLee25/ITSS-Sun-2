package app.hanare.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.hanare.common.ApiException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** H-02: giới hạn đăng nhập sai — logic thuần, đồng hồ giả (không phụ thuộc thời gian thật). */
class LoginThrottleTest {

	private static final long SEC = 1_000_000_000L;

	private final AtomicLong now = new AtomicLong(1_000 * SEC);
	private final LoginThrottle throttle = new LoginThrottle(now::get);

	private void fail(String ip, String email, int times) {
		for (int i = 0; i < times; i++) {
			throttle.check(ip, email);
			throttle.recordFailure(ip, email);
			now.addAndGet(SEC);
		}
	}

	@Test
	void blocksTheSixthAttemptEvenWithTheRightPasswordAndTellsHowLongToWait() {
		fail("1.1.1.1", "a@x.test", 5);
		assertThatThrownBy(() -> throttle.check("1.1.1.1", "a@x.test")).isInstanceOf(ApiException.class).satisfies(e -> {
			ApiException ex = (ApiException) e;
			assertThat(ex.code()).isEqualTo("RATE_LIMITED");
			assertThat(ex.status().value()).isEqualTo(429);
			assertThat(ex.getMessage()).containsPattern("sau \\d+ giây");
		});
	}

	@Test
	void fourFailuresAreStillAllowed() {
		fail("1.1.1.1", "a@x.test", 4);
		assertThatCode(() -> throttle.check("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
	}

	@Test
	void unblocksOnlyAfterTheOldestFailureLeavesTheWindow() {
		fail("1.1.1.1", "a@x.test", 5); // 5 lần sai cách nhau 1s; lần sai đầu tại t0
		now.addAndGet((LoginThrottle.WINDOW_SECONDS - 6) * SEC); // chưa đủ cửa sổ so với lần sai đầu
		assertThatThrownBy(() -> throttle.check("1.1.1.1", "a@x.test")).isInstanceOf(ApiException.class);
		now.addAndGet(2 * SEC);
		assertThatCode(() -> throttle.check("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
	}

	@Test
	void successResetsTheAccountCounter() {
		fail("1.1.1.1", "a@x.test", 4);
		throttle.reset("1.1.1.1", "a@x.test");
		fail("1.1.1.1", "a@x.test", 4);
		assertThatCode(() -> throttle.check("1.1.1.1", "a@x.test")).doesNotThrowAnyException();
	}

	@Test
	void otherAccountsAndOtherAddressesAreNotLockedOut() {
		fail("1.1.1.1", "a@x.test", 5);
		assertThatCode(() -> throttle.check("1.1.1.1", "b@x.test")).doesNotThrowAnyException();
		assertThatCode(() -> throttle.check("2.2.2.2", "a@x.test")).doesNotThrowAnyException();
	}

	@Test
	void guessingManyDifferentEmailsFromOneAddressIsAlsoStopped() {
		for (int i = 0; i < LoginThrottle.MAX_FAILURES_PER_ADDRESS; i++) {
			fail("9.9.9.9", "user" + i + "@x.test", 1);
		}
		assertThatThrownBy(() -> throttle.check("9.9.9.9", "fresh@x.test")).isInstanceOf(ApiException.class)
				.extracting(e -> ((ApiException) e).code()).isEqualTo("RATE_LIMITED");
		assertThatCode(() -> throttle.check("8.8.8.8", "fresh@x.test")).doesNotThrowAnyException();
	}
}
