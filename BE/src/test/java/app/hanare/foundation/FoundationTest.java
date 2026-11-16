package app.hanare.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.GroupRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Context load, /actuator/health, bảo vệ JWT, mẫu lỗi chung, CurrentUser/GroupAccess, Clock thay được. */
@HanareTest
@Import(FoundationTest.Probe.class)
class FoundationTest {

	/** Endpoint chỉ tồn tại trong test này (lớp lồng trong test nên không bị quét vào context khác). */
	@RestController
	static class Probe {

		private final GroupAccess access;

		Probe(GroupAccess access) {
			this.access = access;
		}

		record Me(UUID id, String email) {
		}

		record Body(@NotBlank String name, @Min(1) int count) {
		}

		@GetMapping("/api/v1/_probe/me")
		Me me(CurrentUser user) {
			return new Me(user.id(), user.email());
		}

		@GetMapping("/api/v1/_probe/groups/{projectId}/member")
		GroupMembership member(@PathVariable UUID projectId, CurrentUser user) {
			return access.requireMember(projectId, user);
		}

		@GetMapping("/api/v1/_probe/groups/{projectId}/leader")
		GroupMembership leader(@PathVariable UUID projectId, CurrentUser user) {
			return access.requireLeader(projectId, user);
		}

		@PostMapping("/api/v1/_probe/validate")
		Body validate(@Valid @RequestBody Body body) {
			return body;
		}

		@GetMapping("/api/v1/_probe/boom")
		String boom() {
			throw new IllegalStateException("secret internal detail");
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	DbFixtures fx;
	@Autowired
	TestAuth auth;
	@Autowired
	Clock clock;
	@Autowired
	FixedClock fixedClock;
	@Autowired
	PasswordEncoder passwordEncoder;

	@Test
	void contextLoadsFlywayRanAndClockIsTheTestClock() {
		assertThat(clock).isSameAs(fixedClock);
		assertThat(clock.instant()).isEqualTo(Instant.parse("2026-11-16T03:00:00Z"));
		assertThat(passwordEncoder.matches(DbFixtures.PASSWORD, passwordEncoder.encode(DbFixtures.PASSWORD))).isTrue();
	}

	@Test
	void healthIsPublicAndUp() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void protectedEndpointWithoutTokenIs401WithApiErrorAndTraceId() throws Exception {
		mvc.perform(get("/api/v1/projects"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().exists("X-Trace-Id"))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.fieldErrors").isArray())
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void garbageTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void validTokenReachesControllerAndCurrentUserIsResolved() throws Exception {
		DbFixtures.UserRef u = fx.user("Token User");
		mvc.perform(get("/api/v1/_probe/me").header("Authorization", auth.bearer(u)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(u.id().toString()))
				.andExpect(jsonPath("$.email").value(u.email()));
	}

	@Test
	void unknownRouteWithTokenIs404ApiError() throws Exception {
		DbFixtures.UserRef u = fx.user("Lost User");
		mvc.perform(get("/api/v1/does-not-exist").header("Authorization", auth.bearer(u)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.traceId").isNotEmpty());
	}

	@Test
	void tokenExpiresAccordingToTheApplicationClock() throws Exception {
		DbFixtures.UserRef u = fx.user("Expiring User");
		String bearer = auth.bearer(u);
		mvc.perform(get("/api/v1/_probe/me").header("Authorization", bearer)).andExpect(status().isOk());
		Instant original = clock.instant();
		try {
			fixedClock.advance(Duration.ofHours(9)); // ttl mặc định 8 giờ
			mvc.perform(get("/api/v1/_probe/me").header("Authorization", bearer))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
		}
		finally {
			fixedClock.set(original);
		}
	}

	@Test
	void groupAccessHidesGroupsFromOutsidersAndSeparatesRoles() throws Exception {
		GroupRef g = fx.group("Access", 3);
		DbFixtures.UserRef member = g.members().get(0);
		DbFixtures.UserRef outsider = fx.user("Outsider");
		String base = "/api/v1/_probe/groups/" + g.projectId();

		mvc.perform(get(base + "/leader").header("Authorization", auth.bearer(g.leader())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("LEAD"));
		mvc.perform(get(base + "/member").header("Authorization", auth.bearer(member)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MEMBER"));
		// Member vào thao tác của TL -> 403
		mvc.perform(get(base + "/leader").header("Authorization", auth.bearer(member)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEAD_ONLY"));
		// người ngoài nhóm -> 404 giống nhóm không tồn tại (không lộ sự tồn tại)
		mvc.perform(get(base + "/member").header("Authorization", auth.bearer(outsider)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		mvc.perform(get("/api/v1/_probe/groups/" + UUID.randomUUID() + "/member").header("Authorization", auth.bearer(outsider)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		// thành viên đã rời nhóm mất quyền
		jdbc(g, member);
		mvc.perform(get(base + "/member").header("Authorization", auth.bearer(member)))
				.andExpect(status().isNotFound());
	}

	@Autowired
	org.springframework.jdbc.core.simple.JdbcClient jdbcClient;

	private void jdbc(GroupRef g, DbFixtures.UserRef member) {
		jdbcClient.sql("UPDATE project_members SET left_at = now() WHERE project_id = :p AND user_id = :u")
				.param("p", g.projectId()).param("u", member.id()).update();
	}

	@Test
	void validationErrorsUseCommonShape() throws Exception {
		DbFixtures.UserRef u = fx.user("Validator");
		mvc.perform(post("/api/v1/_probe/validate").header("Authorization", auth.bearer(u))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"count\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(2))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='count')]").exists())
				.andExpect(jsonPath("$.traceId").isNotEmpty());
		mvc.perform(post("/api/v1/_probe/validate").header("Authorization", auth.bearer(u))
				.contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void unexpectedExceptionIs500WithoutLeakingDetails() throws Exception {
		DbFixtures.UserRef u = fx.user("Crash User");
		mvc.perform(get("/api/v1/_probe/boom").header("Authorization", auth.bearer(u)))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
	}

	@Test
	void corsAllowsTheFrontendDevOrigin() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/projects")
				.header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
	}
}
