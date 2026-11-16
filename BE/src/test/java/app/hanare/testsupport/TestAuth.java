package app.hanare.testsupport;

import app.hanare.security.JwtService;
import java.util.UUID;

/** Tạo header {@code Authorization} hợp lệ cho MockMvc: {@code .header("Authorization", auth.bearer(user.id()))}. */
public class TestAuth {

	private final JwtService jwtService;

	public TestAuth(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	public String token(UUID userId, String email) {
		return jwtService.issue(userId, email).token();
	}

	public String bearer(UUID userId, String email) {
		return "Bearer " + token(userId, email);
	}

	public String bearer(DbFixtures.UserRef user) {
		return bearer(user.id(), user.email());
	}
}
