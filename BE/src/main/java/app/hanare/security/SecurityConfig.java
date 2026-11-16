package app.hanare.security;

import app.hanare.common.ErrorCode;
import app.hanare.config.HanareProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT (HS256). Công khai: {@code /api/v1/auth/**} (trừ {@code /api/v1/auth/me}) và
 * {@code /actuator/health}. Còn lại cần {@code Authorization: Bearer}.
 * CSRF tắt vì không dùng cookie/session.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(HanareProperties.class)
public class SecurityConfig {

	private static final int MIN_SECRET_BYTES = 32;

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecretKey jwtSecretKey(HanareProperties props) {
		String secret = props.jwt().secret();
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"HANARE_JWT_SECRET (hanare.jwt.secret) phải có ít nhất 32 byte. Đặt biến môi trường HANARE_JWT_SECRET "
							+ "hoặc dùng profile dev/test.");
		}
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSecretKey, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
		JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
		timestamps.setClock(clock); // hết hạn tính theo Clock của ứng dụng (test dùng đồng hồ cố định)
		OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(timestamps,
				new JwtIssuerValidator(JwtService.ISSUER));
		decoder.setJwtValidator(validator);
		return decoder;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errors,
			@Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
			throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.cors(c -> c.configurationSource(cors))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/api/v1/auth/me").authenticated()
						.requestMatchers("/api/v1/auth/**").permitAll()
						// PUBLIC theo hợp đồng §1.2 (lời mời: nếu có token thì controller thêm alreadyMember/groupId)
						.requestMatchers(HttpMethod.GET, "/api/v1/auth/config", "/api/v1/invites/*", "/api/v1/demo/accounts")
						.permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(o -> o
						.jwt(Customizer.withDefaults())
						.authenticationEntryPoint((req, res, ex) -> errors.write(res, unauthenticatedCode(ex)))
						.accessDeniedHandler((req, res, ex) -> errors.write(res, ErrorCode.FORBIDDEN)))
				.exceptionHandling(e -> e
						.authenticationEntryPoint((req, res, ex) -> errors.write(res, unauthenticatedCode(ex)))
						.accessDeniedHandler((req, res, ex) -> errors.write(res, ErrorCode.FORBIDDEN)));
		return http.build();
	}

	/** Token hết hạn -> TOKEN_EXPIRED (FE về trang đăng nhập); mọi trường hợp khác -> UNAUTHENTICATED. */
	private static ErrorCode unauthenticatedCode(AuthenticationException ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains("expired")) {
				return ErrorCode.TOKEN_EXPIRED;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return ErrorCode.UNAUTHENTICATED;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(HanareProperties props) {
		CorsConfiguration cfg = new CorsConfiguration();
		cfg.setAllowedOrigins(props.cors().allowedOrigins());
		cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "X-Request-Id"));
		cfg.setExposedHeaders(List.of("X-Trace-Id"));
		cfg.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cfg);
		return source;
	}
}
