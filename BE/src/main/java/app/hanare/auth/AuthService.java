package app.hanare.auth;

import app.hanare.auth.AuthDtos.AuthConfigResponse;
import app.hanare.auth.AuthDtos.AuthResponse;
import app.hanare.auth.AuthDtos.LoginRequest;
import app.hanare.auth.AuthDtos.RegisterRequest;
import app.hanare.auth.AuthDtos.UserProfile;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.security.CurrentUser;
import app.hanare.security.JwtService;
import app.hanare.security.JwtService.IssuedToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Đăng ký / đăng nhập email + mật khẩu (BCrypt), Google ID token thật, hồ sơ của tôi. */
@Service
class AuthService {

	static final int PASSWORD_MIN_LENGTH = 8;
	private static final int PASSWORD_MAX_BYTES = 72;

	private final JdbcClient jdbc;
	private final PasswordEncoder encoder;
	private final JwtService jwt;
	private final Clock clock;
	private final Environment env;
	private final GoogleIdTokenVerifier google;
	private final String googleClientId;
	/** Băm giả để thời gian phản hồi không lộ email có tồn tại hay không. */
	private final String dummyHash;

	AuthService(JdbcClient jdbc, PasswordEncoder encoder, JwtService jwt, Clock clock, Environment env,
			GoogleIdTokenVerifier google, @Value("${hanare.google.client-id:}") String googleClientId) {
		this.jdbc = jdbc;
		this.encoder = encoder;
		this.jwt = jwt;
		this.clock = clock;
		this.env = env;
		this.google = google;
		this.googleClientId = googleClientId == null ? "" : googleClientId.trim();
		this.dummyHash = encoder.encode(UUID.randomUUID().toString());
	}

	AuthConfigResponse config() {
		return new AuthConfigResponse(!googleClientId.isEmpty(), PASSWORD_MIN_LENGTH, env.acceptsProfiles(Profiles.of("demo")));
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private record UserRow(UUID id, String name, String email, String passwordHash, Instant createdAt) {

		UserProfile profile() {
			return new UserProfile(id, name, email, createdAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
		}
	}

	private Optional<UserRow> findByEmail(String email) {
		return jdbc.sql("SELECT id, name, email, password_hash, created_at FROM users WHERE email = :e")
				.param("e", email).query((rs, i) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getString("email"), rs.getString("password_hash"), DbTime.instant(rs, "created_at")))
				.optional();
	}

	@Transactional
	AuthResponse register(RegisterRequest req) {
		if (req.password().getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
			throw ApiException.validation("password", "Mật khẩu dài 8–72 ký tự.");
		}
		String email = normalizeEmail(req.email());
		if (findByEmail(email).isPresent()) {
			throw emailTaken();
		}
		UserRow user;
		try {
			user = insertUser(req.name().trim(), email, encoder.encode(req.password()));
		}
		catch (org.springframework.dao.DuplicateKeyException ex) {
			throw emailTaken();
		}
		return respond(user);
	}

	private static ApiException emailTaken() {
		return ApiException.of(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email này đã được đăng ký. Hãy đăng nhập.");
	}

	private UserRow insertUser(String name, String email, String passwordHash) {
		Instant now = clock.instant();
		UUID id = jdbc.sql("""
				INSERT INTO users (name, email, password_hash, created_at) VALUES (:n, :e, :p, :now) RETURNING id
				""")
				.param("n", name).param("e", email).param("p", passwordHash).param("now", DbTime.toDb(now))
				.query(UUID.class).single();
		return new UserRow(id, name, email, passwordHash, now);
	}

	@Transactional(readOnly = true)
	AuthResponse login(LoginRequest req) {
		if (req.password().getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
			throw ApiException.of(ErrorCode.INVALID_CREDENTIALS, null);
		}
		Optional<UserRow> user = findByEmail(normalizeEmail(req.email()));
		String hash = user.map(UserRow::passwordHash).orElse(null);
		boolean ok = encoder.matches(req.password(), hash != null ? hash : dummyHash) && hash != null;
		if (!ok) {
			throw ApiException.of(ErrorCode.INVALID_CREDENTIALS, null);
		}
		return respond(user.get());
	}

	@Transactional
	AuthResponse loginWithGoogle(String idToken) {
		if (googleClientId.isEmpty()) {
			throw ApiException.of(HttpStatus.NOT_IMPLEMENTED, "OAUTH_NOT_CONFIGURED",
					"Đăng nhập Google chưa được cấu hình trên máy chủ. Hãy dùng email và mật khẩu.");
		}
		GoogleIdTokenVerifier.GoogleIdentity identity = google.verify(idToken)
				.filter(GoogleIdTokenVerifier.GoogleIdentity::emailVerified)
				.orElseThrow(() -> ApiException.of(ErrorCode.INVALID_CREDENTIALS, "Đăng nhập Google không hợp lệ."));
		String email = normalizeEmail(identity.email());
		UserRow user = findByEmail(email).orElseGet(() -> {
			String name = identity.name() == null || identity.name().isBlank() ? email.substring(0, email.indexOf('@'))
					: identity.name().trim();
			return insertUser(name.length() > 80 ? name.substring(0, 80) : name, email, null);
		});
		return respond(user);
	}

	@Transactional(readOnly = true)
	UserProfile me() {
		CurrentUser current = CurrentUser.require();
		return jdbc.sql("SELECT id, name, email, password_hash, created_at FROM users WHERE id = :id")
				.param("id", current.id())
				.query((rs, i) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
						rs.getString("password_hash"), DbTime.instant(rs, "created_at")))
				.optional().orElseThrow(() -> ApiException.unauthenticated("Tài khoản không còn tồn tại.")).profile();
	}

	private AuthResponse respond(UserRow user) {
		IssuedToken token = jwt.issue(user.id(), user.email());
		long expiresIn = Duration.between(clock.instant(), token.expiresAt()).toSeconds();
		return new AuthResponse(token.token(), "Bearer", expiresIn,
				token.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), user.profile());
	}
}
