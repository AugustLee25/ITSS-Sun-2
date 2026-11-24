package app.hanare.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.hanare.config.HanareProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** H-01: không còn secret JWT mặc định công khai; demo/dev tự sinh khóa ngẫu nhiên, production phải đặt biến môi trường. */
class JwtSecretTest {

	private static HanareProperties props(String secret) {
		return new HanareProperties(new HanareProperties.Jwt(secret, Duration.ofHours(8)), new HanareProperties.Cors(List.of()));
	}

	private static MockEnvironment profile(String... profiles) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profiles);
		return env;
	}

	@Test
	void demoAndDevGenerateADifferentStrongKeyEveryStartWhenNoSecretIsConfigured() {
		SecurityConfig config = new SecurityConfig();
		for (String p : List.of("demo", "dev")) {
			SecretKey a = config.jwtSecretKey(props(""), profile(p));
			SecretKey b = config.jwtSecretKey(props(null), profile(p));
			assertThat(a.getEncoded()).hasSizeGreaterThanOrEqualTo(32);
			assertThat(a.getEncoded()).isNotEqualTo(b.getEncoded());
		}
	}

	@Test
	void productionStillRefusesToStartWithoutAnExplicitSecret() {
		SecurityConfig config = new SecurityConfig();
		assertThatThrownBy(() -> config.jwtSecretKey(props(""), profile())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> config.jwtSecretKey(props(null), profile("prod"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void anExplicitlyConfiguredSecretIsUsedAsIsAndAShortOneIsStillRejectedEvenInDemo() {
		SecurityConfig config = new SecurityConfig();
		String secret = "explicit-secret-explicit-secret-0123456789";
		assertThat(config.jwtSecretKey(props(secret), profile("demo")).getEncoded()).isEqualTo(secret.getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> config.jwtSecretKey(props("too-short"), profile("demo"))).isInstanceOf(IllegalStateException.class);
	}

	/** H-03: cấu hình chỉ dành cho test không được nằm trong artifact chạy thật, và không file nào ở main được có secret mặc định không rỗng. */
	@Test
	void noConfigShippedInTheJarCarriesAnyNonEmptyDefaultSecret() throws Exception {
		java.nio.file.Path main = java.nio.file.Path.of("src", "main", "resources");
		assertThat(main.resolve("application-test.yml")).as("profile test không được đóng gói vào jar").doesNotExist();
		java.util.regex.Pattern literalDefault = java.util.regex.Pattern.compile("(?im)^\\s*secret:\\s*(?!\\$\\{[A-Z_]+:\\}\\s*$)\\S+");
		try (var files = java.nio.file.Files.walk(main)) {
			for (java.nio.file.Path p : files.filter(f -> f.toString().endsWith(".yml") || f.toString().endsWith(".properties")).toList()) {
				String text = java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
				assertThat(literalDefault.matcher(text).find()).as(p + " có secret mặc định không rỗng").isFalse();
			}
		}
	}

	@Test
	void noPublicDefaultSecretRemainsInTheCommittedProfiles() throws Exception {
		for (String file : List.of("application-demo.yml", "application-dev.yml", "application.yml")) {
			try (var in = getClass().getResourceAsStream("/" + file)) {
				String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				assertThat(text).as(file).doesNotContain("only-hanare-jwt-secret").doesNotContain("change-me-0123456789");
			}
		}
	}
}
