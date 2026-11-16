package app.hanare.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Cấu hình ứng dụng ({@code hanare.*}). */
@ConfigurationProperties(prefix = "hanare")
public record HanareProperties(@DefaultValue Jwt jwt, @DefaultValue Cors cors) {

	/** secret: env HANARE_JWT_SECRET (>= 32 byte). Mặc định chỉ có ở profile dev/test. */
	public record Jwt(String secret, @DefaultValue("PT8H") Duration ttl) {
	}

	public record Cors(@DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
	}
}
