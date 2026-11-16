package app.hanare.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.HanareTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mục A: config, đăng ký, đăng nhập, me, Google khi chưa cấu hình. */
@HanareTest
class AuthTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;

	private String register(String name, String email, String password) {
		return """
				{"name":"%s","email":"%s","password":"%s"}""".formatted(name, email, password);
	}

	@Test
	void configIsPublicAndGoogleDisabledWithoutClientId() throws Exception {
		mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk())
				.andExpect(jsonPath("$.googleEnabled").value(false))
				.andExpect(jsonPath("$.passwordMinLength").value(8))
				.andExpect(jsonPath("$.demoMode").value(false));
	}

	@Test
	void registerNormalizesEmailHashesPasswordAndTokenWorksOnMe() throws Exception {
		String res = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("  Minh  ", "  Minh@Example.COM ", "matkhau-1234")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(28800))
				.andExpect(jsonPath("$.expiresAt").value("2026-11-16T11:00:00Z"))
				.andExpect(jsonPath("$.user.name").value("Minh"))
				.andExpect(jsonPath("$.user.email").value("minh@example.com"))
				.andExpect(jsonPath("$.user.createdAt").value("2026-11-16T03:00:00Z"))
				.andReturn().getResponse().getContentAsString();
		JsonNode body = JSON.readTree(res);
		assertThat(res).doesNotContain("password");
		String hash = jdbc.sql("SELECT password_hash FROM users WHERE email = 'minh@example.com'").query(String.class)
				.single();
		assertThat(hash).startsWith("$2").doesNotContain("matkhau-1234");

		mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + body.get("accessToken").asString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(body.at("/user/userId").asString()))
				.andExpect(jsonPath("$.email").value("minh@example.com"))
				.andExpect(jsonPath("$.createdAt").value("2026-11-16T03:00:00Z"));
	}

	@Test
	void registerRejectsDuplicateEmailCaseInsensitively() throws Exception {
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("An", "an@example.com", "matkhau-1234"))).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("An 2", "AN@Example.com", "matkhau-5678")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
				.andExpect(jsonPath("$.fieldErrors").isArray());
	}

	@Test
	void registerValidatesInput() throws Exception {
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("", "not-an-email", "short")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='email')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='password')]").exists());
		// 72 ký tự nhưng > 72 byte (tiếng Việt có dấu) bị chặn vì BCrypt chỉ dùng 72 byte đầu
		String longBytes = "ế".repeat(40);
		assertThat(longBytes.length()).isEqualTo(40);
		assertThat(longBytes.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("Bình", "binh@example.com", longBytes)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
	}

	@Test
	void loginSucceedsAndFailuresDoNotRevealWhichPartWasWrong() throws Exception {
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("Chi", "chi@example.com", "matkhau-1234"))).andExpect(status().isCreated());

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\" CHI@example.com \",\"password\":\"matkhau-1234\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.email").value("chi@example.com"));

		String wrongPassword = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"chi@example.com\",\"password\":\"sai-mat-khau\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andReturn().getResponse().getContentAsString();
		String unknownEmail = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"khong-ton-tai@example.com\",\"password\":\"matkhau-1234\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andReturn().getResponse().getContentAsString();
		assertThat(JSON.readTree(wrongPassword).get("message")).isEqualTo(JSON.readTree(unknownEmail).get("message"));

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void fixtureUsersCanLoginWithPassword(@Autowired DbFixtures fx) throws Exception {
		DbFixtures.UserRef u = fx.user("Dung");
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(u.email(), DbFixtures.PASSWORD)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.user.userId").value(u.id().toString()));
	}

	@Test
	void meRequiresTokenAndRejectsGarbage() throws Exception {
		mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
	}

	@Test
	void googleLoginIsRejectedClearlyWhenNotConfigured() throws Exception {
		mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"abc\"}"))
				.andExpect(status().isNotImplemented())
				.andExpect(jsonPath("$.code").value("OAUTH_NOT_CONFIGURED"))
				.andExpect(jsonPath("$.message").isNotEmpty());
		mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void publicEndpointsDoNotNeedATokenButOthersDo() throws Exception {
		// /demo/accounts do BE-B cài; ở đây chỉ kiểm tra cổng bảo mật đã mở (404 vì chưa có handler, KHÔNG phải 401)
		mvc.perform(get("/api/v1/demo/accounts")).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/invites/ABCDEFGH")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		mvc.perform(get("/api/v1/groups")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/invites/ABCDEFGH/accept")).andExpect(status().isUnauthorized());
	}
}
