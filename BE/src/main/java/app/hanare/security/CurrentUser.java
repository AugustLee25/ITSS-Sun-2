package app.hanare.security;

import app.hanare.common.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Người dùng đang đăng nhập, lấy từ JWT ({@code sub} = user id, claim {@code email}).
 * Trong controller khai báo tham số {@code CurrentUser user}; trong service dùng {@link #require()}.
 */
public record CurrentUser(UUID id, String email) {

	public static final String EMAIL_CLAIM = "email";

	public static CurrentUser from(Jwt jwt) {
		try {
			return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(EMAIL_CLAIM));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw ApiException.unauthenticated("Token không hợp lệ.");
		}
	}

	public static Optional<CurrentUser> find() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
			return Optional.of(from(jwt));
		}
		return Optional.empty();
	}

	/** Người dùng hiện tại, hoặc 401 nếu chưa xác thực. */
	public static CurrentUser require() {
		return find().orElseThrow(() -> ApiException.unauthenticated(null));
	}
}
