package app.hanare.testsupport;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Trỏ datasource của context test vào một database nhúng mới (Flyway của ứng dụng tự migrate khi khởi động). */
public class HanareTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertyValues.of(
				"spring.datasource.url=" + EmbeddedPg.newDatabase(),
				"spring.datasource.username=" + EmbeddedPg.USER,
				"spring.datasource.password=").applyTo(context);
	}
}
