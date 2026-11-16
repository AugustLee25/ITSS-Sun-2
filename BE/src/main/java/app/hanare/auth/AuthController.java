package app.hanare.auth;

import app.hanare.auth.AuthDtos.AuthConfigResponse;
import app.hanare.auth.AuthDtos.AuthResponse;
import app.hanare.auth.AuthDtos.GoogleLoginRequest;
import app.hanare.auth.AuthDtos.LoginRequest;
import app.hanare.auth.AuthDtos.RegisterRequest;
import app.hanare.auth.AuthDtos.UserProfile;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Mục A của hợp đồng. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final AuthService service;

	AuthController(AuthService service) {
		this.service = service;
	}

	@GetMapping("/config")
	AuthConfigResponse config() {
		return service.config();
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		return service.register(request);
	}

	@PostMapping("/login")
	AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return service.login(request);
	}

	@PostMapping("/google")
	AuthResponse google(@Valid @RequestBody GoogleLoginRequest request) {
		return service.loginWithGoogle(request.idToken());
	}

	@GetMapping("/me")
	UserProfile me() {
		return service.me();
	}
}
