package app.hanare.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Test tích hợp đầy đủ: Spring context + PostgreSQL nhúng mới (Flyway sạch) cho MỖI lớp test + MockMvc.
 * Có sẵn bean để {@code @Autowired}: {@code MockMvc}, {@code JdbcClient}, {@link FixedClock} (thay Clock),
 * {@link DbFixtures}, {@link TestAuth}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = HanareTestInitializer.class)
@Import(TestSupportConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public @interface HanareTest {
}
