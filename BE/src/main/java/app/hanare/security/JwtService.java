package app.hanare.security;

import app.hanare.config.HanareProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Phát JWT HS256 (hạn mặc định 8 giờ, cấu hình {@code hanare.jwt.ttl}). Thời gian lấy từ bean Clock. */
@Service
public class JwtService {

	public static final String ISSUER = "hanare-be";

	/** Token cùng thời điểm hết hạn (UTC) để trả cho FE. */
	public record IssuedToken(String token, Instant expiresAt) {
	}

	private final JwtEncoder encoder;
	private final Clock clock;
	private final HanareProperties props;

	public JwtService(JwtEncoder encoder, Clock clock, HanareProperties props) {
		this.encoder = encoder;
		this.clock = clock;
		this.props = props;
	}

	public IssuedToken issue(UUID userId, String email) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(props.jwt().ttl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(ISSUER)
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(expiresAt)
				.id(UUID.randomUUID().toString())
				.claim(CurrentUser.EMAIL_CLAIM, email)
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
	}
}
