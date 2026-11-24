package app.hanare.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.testsupport.HanareTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** H-02 qua HTTP thật của ứng dụng (MockMvc): 429 sau chuỗi đăng nhập sai, kể cả với mật khẩu đúng. */
@HanareTest
class LoginRateLimitApiTest {

	@Autowired
	MockMvc mvc;

	private ResultActions login(String ip, String email, String password) throws Exception {
		return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		}).content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	private void register(String email) throws Exception {
		mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Hà\",\"email\":\"%s\",\"password\":\"matkhau-1234\"}".formatted(email)))
				.andExpect(status().isCreated());
	}

	@Test
	void fiveWrongPasswordsThenEvenTheRightOneIsRefusedWith429ButOthersAreNotAffected() throws Exception {
		register("victim@example.com");
		register("other@example.com");
		for (int i = 0; i < 5; i++) {
			login("10.0.0.7", "victim@example.com", "sai-mat-khau-" + i).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		}
		login("10.0.0.7", "victim@example.com", "matkhau-1234").andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		// email viết hoa/thừa khoảng trắng vẫn là cùng một tài khoản (cùng khóa)
		login("10.0.0.7", "  VICTIM@Example.com ", "matkhau-1234").andExpect(status().isTooManyRequests());
		// tài khoản khác và địa chỉ khác không bị khóa lây
		login("10.0.0.7", "other@example.com", "matkhau-1234").andExpect(status().isOk());
		login("10.0.0.8", "victim@example.com", "matkhau-1234").andExpect(status().isOk());
	}

	@Test
	void aSuccessfulLoginClearsTheCounterSoLegitimateTyposDoNotAccumulate() throws Exception {
		register("typo@example.com");
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < 4; i++) {
				login("10.0.0.9", "typo@example.com", "sai-" + i).andExpect(status().isUnauthorized());
			}
			login("10.0.0.9", "typo@example.com", "matkhau-1234").andExpect(status().isOk());
		}
	}

	@Test
	void unknownEmailsAreThrottledTheSameWayAsKnownOnesSoThereIsNoUserEnumeration() throws Exception {
		for (int i = 0; i < 5; i++) {
			login("10.0.0.10", "ghost@example.com", "bat-ky-" + i).andExpect(status().isUnauthorized());
		}
		login("10.0.0.10", "ghost@example.com", "bat-ky-6").andExpect(status().isTooManyRequests());
	}
}
