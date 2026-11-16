package app.hanare.testsupport;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Nền cho test DB thuần (không Spring): mỗi lớp test = một database PostgreSQL nhúng MỚI + Flyway migrate từ đầu.
 * Dùng {@link #jdbc} để chạy SQL và {@link #fx} để tạo user/nhóm/kỳ/task/quỹ điểm mẫu.
 * Test cần cả Spring context (MockMvc, JWT) thì dùng {@link HanareTest}.
 */
public abstract class AbstractDbTest {

	protected static DataSource dataSource;
	protected static JdbcClient jdbc;
	protected static DbFixtures fx;

	@BeforeAll
	static void createFreshDatabase() {
		String url = EmbeddedPg.newDatabase();
		dataSource = new DriverManagerDataSource(url, EmbeddedPg.USER, "");
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
		jdbc = JdbcClient.create(dataSource);
		fx = new DbFixtures(jdbc);
	}
}
