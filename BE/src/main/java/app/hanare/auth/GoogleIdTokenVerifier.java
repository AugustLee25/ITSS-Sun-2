package app.hanare.auth;

import java.util.Optional;

/** Xác minh Google ID token thật (chữ ký JWKS của Google, issuer, audience = client id, hạn). Không giả lập. */
public interface GoogleIdTokenVerifier {

	/** @return danh tính đã xác minh, hoặc rỗng nếu token không hợp lệ/hết hạn/sai audience. */
	Optional<GoogleIdentity> verify(String idToken);

	record GoogleIdentity(String email, String name, boolean emailVerified) {
	}
}
