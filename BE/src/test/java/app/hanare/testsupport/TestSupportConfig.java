package app.hanare.testsupport;

import app.hanare.security.JwtService;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bean dùng chung cho test có Spring: {@link FixedClock} (thay Clock của ứng dụng), {@link DbFixtures}, {@link TestAuth}.
 * Đồng hồ khởi tạo ở {@value #DEFAULT_NOW} (thứ Hai 16/11/2026 10:00 giờ VN, nằm trong kỳ (13/11 22:00, 20/11 22:00]
 * của {@link DbFixtures#PROJECT_START}). Test đổi giờ bằng {@code clock.set(...)}/{@code advance(...)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

	public static final String DEFAULT_NOW = "2026-11-16T03:00:00Z";

	@Bean
	@Primary
	FixedClock fixedClock() {
		return new FixedClock(Instant.parse(DEFAULT_NOW));
	}

	@Bean
	DbFixtures dbFixtures(JdbcClient jdbc) {
		return new DbFixtures(jdbc);
	}

	@Bean
	TestAuth testAuth(JwtService jwtService) {
		return new TestAuth(jwtService);
	}
}
