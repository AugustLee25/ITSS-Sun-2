package app.hanare.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Chỉ profile demo: thay bean {@link java.time.Clock} bằng {@link DemoClock}. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoConfig {

	@Bean
	DemoClock clock() {
		return new DemoClock();
	}
}
