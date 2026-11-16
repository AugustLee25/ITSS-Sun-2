package app.hanare.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Tải JWKS của Google ({@code https://www.googleapis.com/oauth2/v3/certs}) khi cần và kiểm tra ID token. */
@Component
class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

	static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
	private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

	private final String clientId;
	private final Clock clock;
	private volatile JwtDecoder decoder;

	NimbusGoogleIdTokenVerifier(@Value("${hanare.google.client-id:}") String clientId, Clock clock) {
		this.clientId = clientId == null ? "" : clientId.trim();
		this.clock = clock;
	}

	private JwtDecoder decoder() {
		JwtDecoder d = decoder;
		if (d == null) {
			synchronized (this) {
				if (decoder == null) {
					NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
					JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(60));
					timestamps.setClock(clock);
					nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, issuerAndAudience()));
					decoder = nimbus;
				}
				d = decoder;
			}
		}
		return d;
	}

	private OAuth2TokenValidator<Jwt> issuerAndAudience() {
		return jwt -> {
			boolean issuerOk = jwt.getIssuer() != null && ISSUERS.contains(jwt.getIssuer().toString());
			boolean audienceOk = jwt.getAudience() != null && jwt.getAudience().contains(clientId);
			if (issuerOk && audienceOk) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Sai issuer hoặc audience", null));
		};
	}

	@Override
	public Optional<GoogleIdentity> verify(String idToken) {
		if (clientId.isEmpty()) {
			return Optional.empty();
		}
		try {
			Jwt jwt = decoder().decode(idToken);
			String email = jwt.getClaimAsString("email");
			if (email == null || email.isBlank()) {
				return Optional.empty();
			}
			String name = jwt.getClaimAsString("name");
			Object verified = jwt.getClaim("email_verified");
			boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
			return Optional.of(new GoogleIdentity(email, name, emailVerified));
		}
		catch (JwtException ex) {
			return Optional.empty();
		}
	}
}
