package app.hanare.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.auth.GoogleIdTokenVerifier.GoogleIdentity;
import app.hanare.testsupport.HanareTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Google bật khi có {@code HANARE_GOOGLE_CLIENT_ID}; việc xác minh token được thay bằng mock (không gọi Google). */
@HanareTest
@TestPropertySource(properties = "hanare.google.client-id=test-client-id.apps.googleusercontent.com")
class AuthGoogleTest {

	@Autowired
	MockMvc mvc;
	@MockitoBean
	GoogleIdTokenVerifier verifier;

	@Test
	void configReportsGoogleEnabled() throws Exception {
		mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.googleEnabled").value(true));
	}

	@Test
	void validIdTokenCreatesUserOnceAndLogsInAgain() throws Exception {
		when(verifier.verify("good")).thenReturn(Optional.of(new GoogleIdentity("Lan@Gmail.com", "Lan", true)));
		String first = mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"good\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.email").value("lan@gmail.com")).andExpect(jsonPath("$.user.name").value("Lan"))
				.andReturn().getResponse().getContentAsString();
		String second = mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"good\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		org.assertj.core.api.Assertions.assertThat(first).contains(second.split("\"userId\":\"")[1].substring(0, 36));
		// tài khoản Google chưa có mật khẩu → không đăng nhập bằng mật khẩu được
		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"lan@gmail.com\",\"password\":\"bat-ky-mat-khau\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void invalidOrUnverifiedTokenIs401() throws Exception {
		when(verifier.verify(anyString())).thenReturn(Optional.empty());
		mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"bad\"}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		when(verifier.verify("unverified")).thenReturn(Optional.of(new GoogleIdentity("x@gmail.com", "X", false)));
		mvc.perform(post("/api/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"unverified\"}")).andExpect(status().isUnauthorized());
	}
}
