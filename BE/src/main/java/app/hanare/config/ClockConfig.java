package app.hanare.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Mọi code đọc "bây giờ" phải qua bean {@link Clock} này (test thay bằng đồng hồ cố định). */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	@Profile("!demo") // profile demo dùng DemoClock (điều khiển được)
	Clock clock() {
		return Clock.systemUTC();
	}
}
