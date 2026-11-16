package app.hanare.demo;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.testsupport.HanareTestInitializer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Profile demo thật (cổng ngẫu nhiên + PG nhúng): dữ liệu mẫu nạp qua API, mọi tài khoản đăng nhập được, idempotent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@ContextConfiguration(initializers = HanareTestInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoTest {

	@Autowired
	Environment env;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	DemoDataLoader loader;
	@Autowired
	DemoClock clock;
	JsonMapper mapper = JsonMapper.builder().build();

	RestClient http() {
		return RestClient.create("http://localhost:" + env.getProperty("local.server.port") + "/api/v1");
	}

	JsonNode get(String path, String token) {
		RestClient.RequestHeadersSpec<?> spec = http().get().uri(path);
		if (token != null) {
			spec = spec.header("Authorization", "Bearer " + token);
		}
		return mapper.readTree(spec.retrieve().body(String.class));
	}

	String login(String email) {
		return mapper.readTree(http().post().uri("/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.body("{\"email\":\"" + email + "\",\"password\":\"Demo@1234\"}").retrieve().body(String.class)).path("accessToken")
				.asString();
	}

	int count(String sql) {
		return jdbc.sql(sql).query(Integer.class).single();
	}

	@Test
	void seededGroupCoversEveryScreenAndLoaderIsIdempotent() {
		assertThat(loader.isReady()).isTrue();
		JsonNode accounts = get("/demo/accounts", null);
		String g = accounts.path("groupId").asString();
		assertThat(accounts.path("items")).hasSize(5);
		assertThat(accounts.at("/items/0/role").asString()).isEqualTo("LEAD");
		assertThat(get("/auth/config", null).path("demoMode").asBoolean()).isTrue();
		assertThat(clock.instant()).isEqualTo(DemoDataLoader.DEMO_NOW);

		String tl = null;
		String an = null;
		String cuc = null;
		for (JsonNode a : accounts.path("items")) {
			String token = login(a.path("email").asString());
			assertThat(a.path("password").asString()).isEqualTo("Demo@1234");
			for (String p : List.of("/groups", "/groups/" + g + "/dashboard", "/groups/" + g + "/tasks?size=50", "/notifications",
					"/groups/" + g + "/points/me", "/groups/" + g + "/help-requests", "/groups/" + g + "/point-policy",
					"/groups/" + g + "/checkpoint-cycles/current", "/groups/" + g + "/risk-reports", "/demo/clock")) {
				get(p, token);
			}
			assertThat(get("/notifications", token).path("items").size()).as("thông báo của %s", a.path("name").asString()).isPositive();
			if (a.path("email").asString().startsWith("tl@")) {
				tl = token;
			}
			else if (a.path("email").asString().startsWith("an@")) {
				an = token;
			}
			else if (a.path("email").asString().startsWith("cuc@")) {
				cuc = token;
			}
		}
		// điểm: A 3,3333 / 6,6667 / 10,0000 (đã ghi 3,3333 + 6,6667), C 5 task Khó mỗi task 4,0000
		assertThat(get("/groups/" + g + "/points/me", an).at("/confirmedPoints/percentLabel").asString()).isEqualTo("10,0000");
		assertThat(get("/groups/" + g + "/points/me", an).at("/potentialPoints/percentLabel").asString()).isEqualTo("20,0000");
		JsonNode allocation = get("/groups/" + g + "/point-policy", tl).at("/allocation/members");
		for (JsonNode m : allocation) {
			String name = m.at("/user/name").asString();
			if (name.equals("Nguyễn Văn An")) {
				assertThat(m.path("tasks").findValuesAsString("percentLabel")).containsExactlyInAnyOrder("3,3333", "6,6667", "10,0000");
			}
			if (name.equals("Trần Thị Cúc")) {
				assertThat(m.path("tasks")).hasSize(5);
				assertThat(m.path("tasks").findValuesAsString("percentLabel")).containsOnly("4,0000");
				assertThat(m.path("tasks").findValuesAsString("difficulty")).containsOnly("HARD");
			}
		}
		assertThat(get("/groups/" + g + "/help-requests?tab=OPEN", cuc).path("items")).hasSize(1);
		assertThat(get("/groups/" + g + "/support-requests?status=OPEN", tl).path("items")).hasSize(1);
		assertThat(count("SELECT count(*) FROM task_result_reviews WHERE decision = 'FIX_REQUIRED'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM point_ledger_entries WHERE entry_type = 'AWARD'")).isEqualTo(3);

		// tua đồng hồ tới giờ nhắc 15:00 thứ Sáu → mọi người nhận đúng một nhắc nhở
		JsonNode set = mapper.readTree(http().post().uri("/demo/clock").header("Authorization", "Bearer " + tl)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{\"now\":\"2026-11-20T08:00:00Z\"}")
				.retrieve().body(String.class));
		assertThat(Instant.parse(set.path("now").asString())).isEqualTo(Instant.parse("2026-11-20T08:00:00Z"));
		assertThat(count("SELECT count(*) FROM notifications n JOIN notification_events e ON e.id = n.event_id "
				+ "WHERE e.event_type = 'CHECKPOINT_REMINDER'")).isEqualTo(5);
		clock.set(DemoDataLoader.DEMO_NOW);

		// idempotent: nạp lại không nhân đôi dữ liệu
		int users = count("SELECT count(*) FROM users");
		int tasks = count("SELECT count(*) FROM tasks");
		loader.onReady();
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
		assertThat(count("SELECT count(*) FROM tasks")).isEqualTo(tasks).isEqualTo(12);
		assertThat(count("SELECT count(*) FROM projects")).isEqualTo(1);
	}
}
