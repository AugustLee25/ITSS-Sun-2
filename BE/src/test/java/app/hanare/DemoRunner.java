package app.hanare;

import app.hanare.testsupport.EmbeddedPg;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Chạy BE ở profile {@code demo} với PostgreSQL NHÚNG (không cần Docker), cổng 8080:
 * {@code BE\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.main-class=app.hanare.DemoRunner"}.
 * Dữ liệu mất khi tắt (mỗi lần chạy nạp lại nhóm mẫu).
 */
public final class DemoRunner {

	private DemoRunner() {
	}

	public static void main(String[] args) {
		// tham số dòng lệnh để thắng application.yml (properties(...) của builder chỉ là giá trị mặc định)
		new SpringApplicationBuilder(HanareBeApplication.class).profiles("demo").run("--spring.datasource.url=" + EmbeddedPg.newDatabase(),
				"--spring.datasource.username=" + EmbeddedPg.USER, "--spring.datasource.password=", "--server.port=8080");
	}
}
