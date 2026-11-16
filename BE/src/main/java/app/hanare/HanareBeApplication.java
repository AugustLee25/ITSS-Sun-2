package app.hanare;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HanareBeApplication {

	static {
		// Mọi thời điểm lưu/hiển thị ở UTC; múi giờ nhóm chỉ dùng khi tính lịch/hiển thị.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	public static void main(String[] args) {
		SpringApplication.run(HanareBeApplication.class, args);
	}

}
