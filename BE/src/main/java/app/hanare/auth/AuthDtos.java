package app.hanare.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** DTO của module auth (khớp {@code docs/api-types.ts}). */
public final class AuthDtos {

	private AuthDtos() {
	}

	public record RegisterRequest(
			@NotBlank(message = "Nhập tên của bạn.") @Size(max = 80, message = "Tên tối đa 80 ký tự.") String name,
			@NotBlank(message = "Nhập email.") @Email(message = "Email không hợp lệ.")
			@Size(max = 254, message = "Email tối đa 254 ký tự.") String email,
			@NotNull(message = "Nhập mật khẩu.") @Size(min = 8, max = 72, message = "Mật khẩu dài 8–72 ký tự.") String password) {

		/** Khoảng trắng thừa quanh email không làm đăng ký thất bại (email được chuẩn hóa chữ thường ở service). */
		public RegisterRequest {
			if (email != null) {
				email = email.trim();
			}
		}
	}

	public record LoginRequest(
			@NotBlank(message = "Nhập email.") String email,
			@NotBlank(message = "Nhập mật khẩu.") String password) {
	}

	public record GoogleLoginRequest(@NotBlank(message = "Thiếu mã đăng nhập Google.") String idToken) {
	}

	public record UserProfile(UUID userId, String name, String email, Instant createdAt) {
	}

	public record AuthResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt, UserProfile user) {
	}

	public record AuthConfigResponse(boolean googleEnabled, int passwordMinLength, boolean demoMode) {
	}
}
