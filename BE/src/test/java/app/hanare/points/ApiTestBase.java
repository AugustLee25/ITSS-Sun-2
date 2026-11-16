package app.hanare.points;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.CycleRef;
import app.hanare.testsupport.DbFixtures.GroupRef;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.TestAuth;
import app.hanare.testsupport.TestSupportConfig;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Nền chung cho test API của support/help/points: MockMvc + dữ liệu "nhóm 5 người" + thông báo giả. */
public abstract class ApiTestBase {

	@Autowired
	protected MockMvc mvc;
	@Autowired
	protected DbFixtures fx;
	@Autowired
	protected TestAuth auth;
	@Autowired
	protected FixedClock clock;
	@Autowired
	protected JdbcClient jdbc;
	@Autowired
	protected RecordingNotifications notes;

	@BeforeEach
	void resetClockAndNotes() {
		clock.set(Instant.parse(TestSupportConfig.DEFAULT_NOW));
		notes.clear();
	}

	/**
	 * Nhóm 5 người: TL (= L), A, B, C, D. Task (kỳ 1, đã duyệt kế hoạch): A có Dễ/TB/Khó (a1,a2,a3), B có 1 TB (b1),
	 * C có 5 Khó (c1..c5), D có 1 Dễ (d1), L có 1 Dễ (l1) + người duyệt độc lập = D. Chưa khóa phương án điểm.
	 */
	public record World(GroupRef group, UserRef lead, UserRef a, UserRef b, UserRef c, UserRef d, CycleRef cycle,
			CycleRef cycle2, Map<String, TaskRef> tasks) {

		public UUID pid() {
			return group.projectId();
		}

		public TaskRef t(String name) {
			return tasks.get(name);
		}
	}

	protected World world() {
		GroupRef g = fx.groupOf5();
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		List<CycleRef> cycles = fx.weeklyCycles(g.projectId(), schedule, 3);
		CycleRef c1 = cycles.get(0);
		UserRef a = g.members().get(0);
		UserRef b = g.members().get(1);
		UserRef c = g.members().get(2);
		UserRef d = g.members().get(3);
		UserRef l = g.leader();
		Map<String, TaskRef> tasks = new LinkedHashMap<>();
		Instant dl = c1.closesAt().minus(Duration.ofDays(1));
		tasks.put("a1", fx.approvedTask(g.projectId(), l.id(), a.id(), c1, "EASY", dl));
		tasks.put("a2", fx.approvedTask(g.projectId(), l.id(), a.id(), c1, "MEDIUM", dl));
		tasks.put("a3", fx.approvedTask(g.projectId(), l.id(), a.id(), c1, "HARD", c1.closesAt()));
		tasks.put("b1", fx.approvedTask(g.projectId(), l.id(), b.id(), c1, "MEDIUM", dl));
		for (int i = 1; i <= 5; i++) {
			tasks.put("c" + i, fx.approvedTask(g.projectId(), l.id(), c.id(), c1, "HARD", dl));
		}
		tasks.put("d1", fx.approvedTask(g.projectId(), l.id(), d.id(), c1, "EASY", dl));
		tasks.put("l1", fx.approvedTask(g.projectId(), l.id(), l.id(), c1, "EASY", dl));
		jdbc.sql("""
				UPDATE tasks SET independent_approver_id = :d, independent_approver_set_by = :l, independent_approver_set_at = now()
				 WHERE id = :t
				""").param("d", d.id()).param("l", l.id()).param("t", tasks.get("l1").taskId()).update();
		return new World(g, l, a, b, c, d, c1, cycles.get(1), tasks);
	}

	/** Khóa phương án điểm qua API (mặc định mọi thành viên, R_i = 0). */
	protected void activate(World w) throws Exception {
		call(w.lead(), post("/api/v1/groups/" + w.pid() + "/point-policy/activate").contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key()).content("{\"confirmedWithMembers\":true}"), 201);
	}

	// ------------------------------------------------------------------------------------------------ HTTP helpers

	protected static String key() {
		return UUID.randomUUID().toString();
	}

	protected MvcResult call(UserRef user, MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
		MockHttpServletRequestBuilder b = user == null ? req : req.header("Authorization", auth.bearer(user));
		return mvc.perform(b).andExpect(status().is(expectedStatus)).andReturn();
	}

	protected MvcResult getOk(UserRef user, String path) throws Exception {
		return call(user, get(path), 200);
	}

	protected MvcResult getStatus(UserRef user, String path, int status) throws Exception {
		return call(user, get(path), status);
	}

	protected MvcResult postJson(UserRef user, String path, String json, int status) throws Exception {
		return call(user, post(path).contentType(MediaType.APPLICATION_JSON).content(json), status);
	}

	protected MvcResult postIdem(UserRef user, String path, String json, int status) throws Exception {
		return call(user, post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key()).content(json),
				status);
	}

	protected MvcResult patchJson(UserRef user, String path, String json, int status) throws Exception {
		return call(user, patch(path).contentType(MediaType.APPLICATION_JSON).content(json), status);
	}

	protected static String body(MvcResult r) throws Exception {
		return r.getResponse().getContentAsString();
	}

	protected static <T> T at(MvcResult r, String jsonPath) throws Exception {
		return JsonPath.read(body(r), jsonPath);
	}

	protected static String errorCode(MvcResult r) throws Exception {
		return JsonPath.read(body(r), "$.code");
	}

	protected static UUID uuid(MvcResult r, String jsonPath) throws Exception {
		return UUID.fromString(JsonPath.read(body(r), jsonPath));
	}

	protected long count(String sql, Object... params) {
		var spec = jdbc.sql(sql);
		for (int i = 0; i < params.length; i++) {
			spec = spec.param("p" + (i + 1), params[i]);
		}
		return spec.query(Long.class).single();
	}
}
