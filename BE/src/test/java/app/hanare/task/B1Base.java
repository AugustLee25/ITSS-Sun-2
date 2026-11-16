package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.CycleRef;
import app.hanare.testsupport.DbFixtures.GroupRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Nền chung cho test BE-B1: nhóm 5 người + 3 kỳ tuần, gọi API qua MockMvc, đọc JSON bằng JsonPath. */
@HanareTest
@Import(B1TestConfig.class)
abstract class B1Base {

	@Autowired
	MockMvc mvc;
	@Autowired
	DbFixtures fx;
	@Autowired
	TestAuth auth;
	@Autowired
	FixedClock clock;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	JsonMapper mapper;
	@Autowired
	B1TestConfig.RecordingNotifications notes;
	@Autowired
	B1TestConfig.RecordingHook hook;

	static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
	/** 20:00 giờ VN thứ Năm 19/11/2026 (deadline mẫu, thuộc kỳ 1). */
	static final Instant DL = Instant.parse("2026-11-19T13:00:00Z");
	static final String HTTPS_EVIDENCE = "[{\"type\":\"URL\",\"url\":\"https://github.com/x/y/pull/7\",\"label\":\"PR 7\"}]";

	@BeforeEach
	void resetClock() {
		clock.set(NOW);
		notes.clear();
		hook.reset();
	}

	/** Nhóm 5 người (lead, a, b, c, d), 3 kỳ: kỳ 1 = (13/11 15:00Z, 20/11 15:00Z] đang OPEN. */
	record World(GroupRef g, UserRef lead, UserRef a, UserRef b, UserRef c, UserRef d, List<CycleRef> cycles) {
		UUID pid() {
			return g.projectId();
		}

		CycleRef c1() {
			return cycles.get(0);
		}

		CycleRef c2() {
			return cycles.get(1);
		}
	}

	record Task(UUID taskId, UUID roundId, UUID planId) {
	}

	World world() {
		GroupRef g = fx.groupOf5();
		UUID sched = fx.schedule(g.projectId(), g.leader().id());
		List<CycleRef> cycles = fx.weeklyCycles(g.projectId(), sched, 3);
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'OPEN' WHERE id = :c").param("c", cycles.get(0).id()).update();
		return new World(g, g.leader(), g.members().get(0), g.members().get(1), g.members().get(2), g.members().get(3), cycles);
	}

	String body(Object... kv) {
		return mapper.writeValueAsString(J.o(kv));
	}

	MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UserRef u) {
		return b.header("Authorization", auth.bearer(u)).contentType(MediaType.APPLICATION_JSON);
	}

	MvcResult call(MockHttpServletRequestBuilder b, UserRef u, int status) throws Exception {
		MvcResult r = mvc.perform(as(b, u)).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

	MvcResult GET(String url, UserRef u, int status) throws Exception {
		return call(get(url), u, status);
	}

	MvcResult POST(String url, UserRef u, String json, int status) throws Exception {
		return call(post(url).content(json), u, status);
	}

	MvcResult PATCH(String url, UserRef u, String json, int status) throws Exception {
		return call(patch(url).content(json), u, status);
	}

	MvcResult PUT(String url, UserRef u, String json, int status) throws Exception {
		return call(put(url).content(json), u, status);
	}

	static <T> T at(MvcResult r, String path) throws Exception {
		return JsonPath.read(r.getResponse().getContentAsString(), path);
	}

	static UUID uuidAt(MvcResult r, String path) throws Exception {
		return UUID.fromString(at(r, path));
	}

	static String code(MvcResult r) throws Exception {
		return at(r, "$.code");
	}

	static long longAt(MvcResult r, String path) throws Exception {
		return ((Number) at(r, path)).longValue();
	}

	/** Tạo task qua API (LEAD); trả TaskDetail (planStatus DRAFT). */
	MvcResult createTask(World w, UserRef assignee, String title, String difficulty, Instant deadline) throws Exception {
		return POST("/api/v1/groups/" + w.pid() + "/tasks", w.lead(), body("title", title, "assigneeId", assignee.id(), "cycleId",
				w.c1().id(), "description", "Phạm vi " + title, "expectedOutput", "Đầu ra " + title, "startAt", "2026-11-16T01:00:00Z",
				"deadline", deadline.toString(), "difficulty", difficulty), 201);
	}

	/** Tạo + gửi + duyệt kế hoạch (kỳ 1). */
	Task approvedTask(World w, UserRef assignee, String title, String difficulty, Instant deadline) throws Exception {
		MvcResult t = createTask(w, assignee, title, difficulty, deadline);
		UUID taskId = uuidAt(t, "$.id");
		UUID planId = uuidAt(t, "$.pendingRevision.id");
		MvcResult s = POST("/api/v1/plans/" + planId + "/submit", assignee, body("version", longAt(t, "$.pendingRevision.version")), 200);
		POST("/api/v1/plans/" + planId + "/review", w.lead(), body("decision", "APPROVED", "version", longAt(s, "$.plan.version")), 200);
		return new Task(taskId, uuidAt(t, "$.roundId"), planId);
	}

	MvcResult submitResult(Task t, UserRef who, int status) throws Exception {
		return POST("/api/v1/task-rounds/" + t.roundId() + "/submissions", who,
				"{\"planRevisionId\":\"" + effectivePlan(t) + "\",\"resultNote\":\"Xong\",\"evidence\":" + HTTPS_EVIDENCE + "}", status);
	}

	UUID effectivePlan(Task t) {
		return jdbc.sql("SELECT effective_plan_id FROM task_review_rounds WHERE id = :r").param("r", t.roundId()).query(UUID.class).single();
	}

	@Autowired
	CycleLifecycle lifecycle;

	/** Đóng kỳ như job của BE-A: chuyển giờ qua giờ chốt, status CLOSED, gọi CutoffHook. */
	void closeCycle(World w, CycleRef c) {
		clock.set(c.closesAt().plusSeconds(1));
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'CLOSED', closed_at = :at WHERE id = :c")
				.param("at", app.hanare.common.DbTime.toDb(clock.instant())).param("c", c.id()).update();
		lifecycle.onCycleClosed(new app.hanare.schedule.CycleService.CycleRef(c.id(), w.pid(), c.sequenceNo(), null, c.periodStart(),
				c.periodStart(), c.closesAt().minusSeconds(7 * 3600), c.closesAt(), "CLOSED", clock.instant(), null));
	}
}
