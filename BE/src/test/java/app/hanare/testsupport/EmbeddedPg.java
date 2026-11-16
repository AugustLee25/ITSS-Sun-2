package app.hanare.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Một PostgreSQL nhúng duy nhất cho cả JVM test (khởi động lười, tắt khi JVM thoát);
 * mỗi lần gọi {@link #newDatabase()} tạo một database MỚI nên mỗi lớp test có schema sạch.
 */
public final class EmbeddedPg {

	public static final String USER = "postgres";

	private static final AtomicInteger SEQ = new AtomicInteger();
	private static EmbeddedPostgres server;

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private EmbeddedPg() {
	}

	private static synchronized EmbeddedPostgres server() {
		if (server == null) {
			try {
				server = EmbeddedPostgres.builder().start();
			}
			catch (IOException ex) {
				throw new IllegalStateException("Không khởi động được PostgreSQL nhúng", ex);
			}
			EmbeddedPostgres started = server;
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					started.close();
				}
				catch (IOException ignored) {
					// tắt JVM, bỏ qua
				}
			}));
		}
		return server;
	}

	/** Tạo database rỗng mới và trả JDBC URL (đã kèm user=postgres, không mật khẩu). */
	public static String newDatabase() {
		EmbeddedPostgres pg = server();
		String name = "hanare_test_" + SEQ.incrementAndGet() + "_" + System.nanoTime();
		try (Connection c = pg.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
			st.execute("CREATE DATABASE \"" + name + "\"");
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Không tạo được database test", ex);
		}
		return pg.getJdbcUrl(USER, name);
	}
}
