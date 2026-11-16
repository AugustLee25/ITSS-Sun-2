package app.hanare.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import app.hanare.schedule.CheckpointJob;
import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nghiệm thu 12.10 xuyên module bằng BEAN THẬT (không stub NotificationService/hook): group + schedule + notification +
 * task + support + help + points qua API, đồng hồ {@link FixedClock} và {@link CheckpointJob} thật.
 */
@HanareTest
class AcceptanceE2ETest {

	static final Instant MON = Instant.parse("2026-11-16T03:00:00Z"); // thứ Hai 10:00 VN

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
	CheckpointJob job;

	@BeforeEach
	void reset() {
		fx.resetData();
		clock.set(MON);
	}

	// ------------------------------------------------------------------------------------------------ khung gọi API

	record Group(String id, UserRef lead, List<UserRef> members, JsonNode cycles) {
		List<UserRef> all() {
			List<UserRef> all = new ArrayList<>(List.of(lead));
			all.addAll(members);
			return all;
		}

		JsonNode cycle(int index) {
			return cycles.get(index);
		}
	}

	record Task(String id, String roundId) {
	}

	JsonNode call(HttpMethod method, String path, UserRef who, Object body, int expected, String idemKey) throws Exception {
		MockHttpServletRequestBuilder b = request(method, "/api/v1" + path).contentType(MediaType.APPLICATION_JSON);
		if (who != null) {
			b.header("Authorization", auth.bearer(who));
		}
		if (method != HttpMethod.GET) {
			b.header("Idempotency-Key", idemKey != null ? idemKey : UUID.randomUUID().toString());
		}
		if (body != null) {
			b.content(mapper.writeValueAsString(body));
		}
		MvcResult r = mvc.perform(b).andReturn();
		String text = r.getResponse().getContentAsString();
		assertThat(r.getResponse().getStatus()).as("%s %s → %s", method, path, text).isEqualTo(expected);
		return text.isBlank() ? mapper.createObjectNode() : mapper.readTree(text);
	}

	JsonNode get(String path, UserRef who) throws Exception {
		return call(HttpMethod.GET, path, who, null, 200, null);
	}

	JsonNode post(String path, UserRef who, Object body, int expected) throws Exception {
		return call(HttpMethod.POST, path, who, body, expected, null);
	}

	static Map<String, Object> m(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	/** Nhóm thật qua API: TL tạo, các thành viên vào bằng mã mời. */
	Group group(String name, int weekday, String cutoff, int memberCount) throws Exception {
		UserRef lead = fx.user("TL " + name);
		JsonNode created = post("/groups", lead, m("name", name, "deadline", "2026-12-25", "scheduleAgreed", true, "schedule",
				m("weekday", weekday, "cutoffTime", cutoff, "timezone", "Asia/Ho_Chi_Minh")), 201);
		String id = created.at("/group/id").asString();
		List<UserRef> members = new ArrayList<>();
		for (int i = 1; i <= memberCount; i++) {
			UserRef u = fx.user("TV" + i + " " + name);
			post("/invites/" + created.at("/invite/code").asString() + "/accept", u, null, 200);
			members.add(u);
		}
		return new Group(id, lead, members, get("/groups/" + id + "/checkpoint-cycles?sort=asc&size=10", lead).path("items"));
	}

	JsonNode createTask(Group g, UserRef assignee, JsonNode cycle, String title, String difficulty, String deadline, int expected)
			throws Exception {
		return post("/groups/" + g.id() + "/tasks", g.lead(), m("title", title, "assigneeId", assignee.id(), "cycleId",
				cycle.path("id").asString(), "description", "Phạm vi " + title, "expectedOutput", "Đầu ra " + title, "startAt",
				"2026-11-16T01:00:00Z", "deadline", deadline, "difficulty", difficulty), expected);
	}

	/** Tạo task + người thực hiện gửi + TL duyệt kế hoạch. */
	Task task(Group g, UserRef assignee, JsonNode cycle, String title, String difficulty, String deadline) throws Exception {
		JsonNode t = createTask(g, assignee, cycle, title, difficulty, deadline, 201);
		String plan = t.at("/pendingRevision/id").asString();
		JsonNode s = post("/plans/" + plan + "/submit", assignee, m("version", t.at("/pendingRevision/version").asLong()), 200);
		post("/plans/" + plan + "/review", g.lead(), m("decision", "APPROVED", "version", s.at("/plan/version").asLong()), 200);
		return new Task(t.path("id").asString(), t.path("roundId").asString());
	}

	JsonNode submit(UserRef who, Task t, int expected) throws Exception {
		JsonNode d = get("/tasks/" + t.id(), who);
		return post("/task-rounds/" + d.at("/currentRound/id").asString() + "/submissions", who,
				m("planRevisionId", d.at("/effectivePlan/id").asString(), "resultNote", "Đã xong",
						"evidence", List.of(m("type", "URL", "url", "https://github.com/x/y/pull/7", "label", "PR"))), expected);
	}

	String lastSubmissionId(UserRef who, String roundId) throws Exception {
		JsonNode items = get("/task-rounds/" + roundId + "/submissions", who).path("items");
		return items.get(items.size() - 1).path("id").asString();
	}

	JsonNode review(Group g, String submissionId, String decision, String issue, int expected, String key) throws Exception {
		return call(HttpMethod.POST, "/task-submissions/" + submissionId + "/review", g.lead(),
				m("decision", decision, "issueDescription", issue), expected, key);
	}

	int count(UserRef who, String type) throws Exception {
		int n = 0;
		for (JsonNode i : get("/notifications?size=100", who).path("items")) {
			if (type.equals(i.path("type").asString())) {
				n++;
			}
		}
		return n;
	}

	void runJob(Instant at) {
		clock.set(at);
		job.runOnce(at);
	}

	int dbCount(String sql) {
		return jdbc.sql(sql).query(Integer.class).single();
	}

	// ------------------------------------------------------------------------------------------------ 12.10 #1, #2

	@Test
	void reminderPerGroupScheduleAndOneLeadSummaryPerCycleEvenWhenJobRepeats() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 2); // chốt thứ Sáu 22:00 → nhắc 15:00
		Group b = group("Nhóm B", 7, "20:00", 2); // chốt Chủ nhật 20:00 → nhắc 13:00
		assertThat(a.cycle(0).path("reminderAt").asString()).isEqualTo("2026-11-20T08:00:00Z");
		assertThat(a.cycle(0).path("closesAt").asString()).isEqualTo("2026-11-20T15:00:00Z");
		assertThat(b.cycle(0).path("reminderAt").asString()).isEqualTo("2026-11-22T06:00:00Z");
		assertThat(b.cycle(0).path("closesAt").asString()).isEqualTo("2026-11-22T13:00:00Z");

		// trước giờ nhắc: chưa ai nhận
		runJob(Instant.parse("2026-11-20T07:59:00Z"));
		assertThat(count(a.lead(), "CHECKPOINT_REMINDER")).isZero();

		// A nhắc đúng 15:00 (lặp job + đồng thời vẫn một thông báo mỗi người, gồm cả TL); B chưa tới giờ
		runJob(Instant.parse("2026-11-20T08:00:00Z"));
		runJobsConcurrently(Instant.parse("2026-11-20T08:05:00Z"));
		for (UserRef u : a.all()) {
			assertThat(count(u, "CHECKPOINT_REMINDER")).as("nhắc nhóm A của %s", u.name()).isEqualTo(1);
		}
		for (UserRef u : b.all()) {
			assertThat(count(u, "CHECKPOINT_REMINDER")).as("nhóm B chưa tới giờ nhắc").isZero();
		}

		// hết kỳ A: đúng MỘT tóm tắt cho TL (kể cả job chạy lặp/đồng thời), thành viên không nhận; leadSummary nối từ module task
		runJobsConcurrently(Instant.parse("2026-11-20T15:00:01Z"));
		runJob(Instant.parse("2026-11-20T15:30:00Z"));
		assertThat(count(a.lead(), "CHECKPOINT_REVIEW_REQUIRED")).isEqualTo(1);
		for (UserRef u : a.members()) {
			assertThat(count(u, "CHECKPOINT_REVIEW_REQUIRED")).isZero();
		}
		String cycleId = a.cycle(0).path("id").asString();
		JsonNode detailLead = get("/groups/" + a.id() + "/checkpoint-cycles/" + cycleId, a.lead());
		assertThat(detailLead.path("leadSummary").isObject()).as("TL thấy leadSummary").isTrue();
		assertThat(detailLead.at("/leadSummary/cycleId").asString()).isEqualTo(cycleId);
		assertThat(detailLead.at("/leadSummary/members/total").asInt()).isEqualTo(3);
		assertThat(get("/groups/" + a.id() + "/checkpoint-cycles/" + cycleId, a.members().get(0)).path("leadSummary").isNull())
				.as("Member không thấy leadSummary").isTrue();
		assertThat(get("/groups/" + a.id() + "/checkpoint-cycles/" + cycleId + "/lead-summary", a.lead()).at("/members/total").asInt())
				.isEqualTo(3);

		// tới giờ nhắc của B (13:00 VN CN)
		runJob(Instant.parse("2026-11-22T06:00:00Z"));
		for (UserRef u : b.all()) {
			assertThat(count(u, "CHECKPOINT_REMINDER")).as("nhắc nhóm B của %s", u.name()).isEqualTo(1);
		}
		for (UserRef u : a.all()) {
			assertThat(count(u, "CHECKPOINT_REMINDER")).as("nhóm A không nhận thêm").isEqualTo(1);
		}
		assertThat(dbCount("SELECT count(*) FROM notification_events WHERE event_type = 'CHECKPOINT_REVIEW_REQUIRED'")).isEqualTo(1);
	}

	void runJobsConcurrently(Instant at) throws Exception {
		clock.set(at);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Object>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				calls.add(() -> job.runOnce(at));
			}
			for (var f : pool.invokeAll(calls)) {
				f.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ------------------------------------------------------------------------------------------------ 12.10 #3, #5, #6, #7

	@Test
	void deadlinesCutoffReviewAfterCutoffAndCarryoverKeepSameTask() throws Exception {
		Group g = group("Nhóm Hạn", 5, "22:00", 2);
		UserRef m1 = g.members().get(0);
		UserRef m2 = g.members().get(1);
		JsonNode k1 = g.cycle(0);
		JsonNode k2 = g.cycle(1);

		// #3 deadline đúng cutoff thuộc kỳ đó; CN sau cutoff thuộc kỳ sau
		createTask(g, m1, k1, "Đúng giờ chốt", "EASY", "2026-11-20T15:00:00Z", 201);
		JsonNode wrong = createTask(g, m1, k1, "Chủ nhật", "EASY", "2026-11-22T13:00:00Z", 422);
		assertThat(wrong.path("code").asString()).isEqualTo("DEADLINE_OUTSIDE_CYCLE");
		createTask(g, m1, k2, "Chủ nhật ở kỳ sau", "EASY", "2026-11-22T13:00:00Z", 201);

		// #5 nộp 19:30 VN (12:30Z), deadline 20:00 VN (13:00Z) thứ Năm; duyệt sau cutoff (hôm sau) vẫn đúng hạn
		Task onTime = task(g, m1, k1, "Nộp 19:30", "MEDIUM", "2026-11-19T13:00:00Z");
		Task toReview = task(g, m2, k1, "Chờ duyệt", "EASY", "2026-11-19T13:00:00Z");
		Task fix = task(g, m2, k1, "Cần sửa", "EASY", "2026-11-19T13:00:00Z");
		Task none = task(g, m1, k1, "Chưa nộp", "EASY", "2026-11-19T13:00:00Z");
		clock.set(Instant.parse("2026-11-19T12:30:00Z"));
		JsonNode sub = submit(m1, onTime, 201);
		assertThat(sub.at("/submission/isOnTime").asBoolean()).isTrue();
		assertThat(sub.at("/task/taskStatus").asString()).isEqualTo("SUBMITTED");
		String onTimeSub = sub.at("/submission/id").asString();
		submit(m2, toReview, 201);
		submit(m2, fix, 201);
		review(g, onTimeSub, "ACCEPTED", null, 409, null); // chưa tới cutoff
		clock.set(Instant.parse("2026-11-20T15:00:00Z")); // đúng cutoff vẫn nộp được (kỳ = (start, closesAt])
		JsonNode atCutoff = submit(m1, task(g, m1, k1, "Sát giờ chốt", "EASY", "2026-11-20T15:00:00Z"), 201);
		assertThat(atCutoff.at("/submission/id").asString()).isNotBlank();
		clock.set(Instant.parse("2026-11-20T15:00:01Z")); // quá cutoff 1 giây: không nộp được nữa
		assertThat(submit(m1, none, 409).path("code").asString()).isEqualTo("SUBMISSION_WINDOW_CLOSED");

		runJob(Instant.parse("2026-11-21T03:00:00Z")); // sáng hôm sau; kỳ đã CLOSED
		JsonNode accepted = review(g, onTimeSub, "ACCEPTED", null, 200, null);
		assertThat(accepted.at("/task/taskStatus").asString()).isEqualTo("DONE");
		assertThat(accepted.at("/task/timeliness").asString()).isEqualTo("ON_TIME_ACCEPTED");
		JsonNode doneTask = get("/tasks/" + onTime.id(), m1);
		assertThat(doneTask.path("completedAt").asString()).isEqualTo(sub.at("/submission/submittedAt").asString());
		assertThat(doneTask.path("acceptedAt").asString()).isNotEqualTo(doneTask.path("completedAt").asString());

		// #7 task chờ duyệt không tự chuyển kỳ / không bị tính trễ; task chưa nộp phải chờ TL chốt
		JsonNode queue = get("/groups/" + g.id() + "/checkpoint-cycles/" + k1.path("id").asString() + "/review-queue", g.lead());
		assertThat(queue.path("awaitingReview").size()).isGreaterThanOrEqualTo(2);
		assertThat(queue.path("carryoverRequired").size()).isEqualTo(1);
		for (JsonNode row : queue.path("awaitingReview")) {
			assertThat(row.at("/row/isLate").asBoolean()).as("TL chậm duyệt không làm task trễ").isFalse();
		}
		String carryBody = "{\"targetCycleId\":\"" + k2.path("id").asString() + "\",\"deadline\":\"2026-11-25T10:00:00Z\"}";
		assertThat(post("/task-rounds/" + toReview.roundId() + "/carryover", g.lead(), mapper.readTree(carryBody), 409).path("code").asString())
				.isEqualTo("CARRYOVER_AWAITING_REVIEW");

		// #6 FIX_REQUIRED bắt buộc mô tả; Member nhận thông báo; carryover cùng task, giữ vòng cũ
		String fixSub = lastSubmissionId(m2, fix.roundId());
		assertThat(review(g, fixSub, "FIX_REQUIRED", null, 400, null).path("code").asString()).isEqualTo("VALIDATION_FAILED");
		review(g, fixSub, "FIX_REQUIRED", "Thiếu phần kiểm thử đơn vị cho module", 200, null);
		assertThat(count(m2, "TASK_REVIEWED")).isEqualTo(1);
		JsonNode carried = post("/task-rounds/" + fix.roundId() + "/carryover", g.lead(),
				m("targetCycleId", k2.path("id").asString(), "deadline", "2026-11-25T10:00:00Z"), 201);
		assertThat(carried.at("/task/id").asString()).isEqualTo(fix.id());
		assertThat(carried.at("/targetRound/roundNo").asInt()).isEqualTo(2);
		assertThat(carried.at("/targetRound/previousRoundId").asString()).isEqualTo(fix.roundId());
		assertThat(carried.at("/sourceRound/state").asString()).isEqualTo("CARRIED_OVER");
		assertThat(get("/task-rounds/" + fix.roundId() + "/submissions", m2).path("items").size()).isEqualTo(1);
		assertThat(count(m2, "TASK_CARRIED_OVER")).isEqualTo(1);
		// task chưa nộp: TL chốt thì chuyển, vẫn cùng taskId
		JsonNode carriedNone = post("/task-rounds/" + none.roundId() + "/carryover", g.lead(),
				m("targetCycleId", k2.path("id").asString(), "deadline", "2026-11-26T10:00:00Z"), 201);
		assertThat(carriedNone.at("/task/id").asString()).isEqualTo(none.id());
		// quyết định kỳ cũ không đổi sau khi chuyển kỳ (#20)
		JsonNode row = get("/groups/" + g.id() + "/dashboard/members/" + m1.id() + "?cycleId=" + k1.path("id").asString(), g.lead());
		assertThat(row.toString()).contains(onTime.id());
	}

	// ------------------------------------------------------------------------------------------------ 12.10 #9–#11, #18, #19

	@Test
	void helpFlowAwardsExactlyOnceKeepsHelperAcrossCarryoverAndHidesPrivateSupport() throws Exception {
		Group g = group("Nhóm Hỗ trợ", 5, "22:00", 4); // TL + A, B, C, D = 5 người, 20% mỗi người
		UserRef a = g.members().get(0);
		UserRef b = g.members().get(1);
		JsonNode k1 = g.cycle(0);
		JsonNode k2 = g.cycle(1);

		// #8 giỏ 1/2/3 của A = 3,3333 / 6,6667 / 10,0000; B có một task (20%)
		task(g, a, k1, "A dễ", "EASY", "2026-11-19T13:00:00Z");
		Task medium = task(g, a, k1, "A trung bình", "MEDIUM", "2026-11-19T13:00:00Z");
		Task hard = task(g, a, k1, "A khó", "HARD", "2026-11-19T13:00:00Z");
		task(g, b, k1, "B việc", "MEDIUM", "2026-11-19T13:00:00Z");
		JsonNode policy = post("/groups/" + g.id() + "/point-policy/activate", g.lead(), m("confirmedWithMembers", true), 201);
		List<String> labels = new ArrayList<>();
		for (JsonNode mem : policy.at("/allocation/members")) {
			if (mem.at("/user/userId").asString().equals(a.id().toString())) {
				for (JsonNode t : mem.path("tasks")) {
					labels.add(t.at("/points/percentLabel").asString());
				}
			}
		}
		assertThat(labels).containsExactlyInAnyOrder("3,3333", "6,6667", "10,0000");

		// #18 Member không đăng yêu cầu công khai; nội dung hỗ trợ riêng không rò
		String secret = "BÍ-MẬT-RIÊNG-TƯ-" + UUID.randomUUID();
		post("/groups/" + g.id() + "/help-requests", a, m("taskId", hard.id(), "beneficiaryId", a.id(), "title", "Tự đăng", "description",
				"Mình muốn tự đăng yêu cầu", "privacyChecked", true), 403);
		JsonNode sr = post("/tasks/" + hard.id() + "/support-requests", a,
				m("obstacleType", "PERSONAL", "description", "Việc nhà: " + secret, "neededSupport", "Nhờ làm thay " + secret), 201);
		String srId = sr.path("id").asString();
		JsonNode hr = post("/groups/" + g.id() + "/help-requests", g.lead(), m("taskId", hard.id(), "beneficiaryId", a.id(),
				"supportRequestId", srId, "title", "Làm thay task khó", "description", "Cần người làm thay toàn bộ task khó",
				"privacyChecked", true), 201);
		assertThat(hr.at("/points/pointUnits").asLong()).isEqualTo(100_000);
		JsonNode offer = post("/help-requests/" + hr.path("id").asString() + "/offers", b,
				m("message", "Mình nhận", "acknowledgedScope", true, "helpRequestVersion", hr.path("version").asLong()), 201);
		JsonNode accept = post("/help-requests/" + hr.path("id").asString() + "/accept-offer", g.lead(),
				m("offerId", offer.path("id").asString(), "version", get("/help-requests/" + hr.path("id").asString(), g.lead()).path("version").asLong()), 200);
		String workId = accept.at("/work/id").asString();

		// #9 A còn 10%, B 30%; chưa có AWARD nào
		assertThat(get("/groups/" + g.id() + "/points/me", a).at("/potentialPoints/pointUnits").asLong()).isEqualTo(33_333 + 66_667); // A còn 10%
		assertThat(get("/groups/" + g.id() + "/points/me", b).at("/potentialPoints/pointUnits").asLong()).isEqualTo(200_000 + 100_000); // B 30%
		assertThat(get("/groups/" + g.id() + "/points/me", b).at("/confirmedPoints/pointUnits").asLong()).isZero();
		assertThat(dbCount("SELECT count(*) FROM point_ledger_entries")).isZero();

		// riêng tư: dashboard, thông báo, yêu cầu công khai, risk không chứa nội dung riêng; người khác đọc đề nghị riêng → 403
		StringBuilder everything = new StringBuilder();
		for (UserRef u : g.all()) {
			everything.append(get("/groups/" + g.id() + "/dashboard", u)).append(get("/notifications?size=100", u))
					.append(get("/groups/" + g.id() + "/help-requests?tab=OPEN&size=100", u))
					.append(get("/groups/" + g.id() + "/risk-reports", u)).append(get("/help-requests/" + hr.path("id").asString(), u));
		}
		assertThat(everything.toString()).doesNotContain(secret);
		call(HttpMethod.GET, "/support-requests/" + srId, b, null, 403, null);
		assertThat(get("/support-requests/" + srId, g.lead()).path("description").asString()).contains(secret);

		// Helper nộp (kỳ 1 còn mở) → TL chưa duyệt được trước cutoff
		clock.set(Instant.parse("2026-11-19T12:00:00Z"));
		JsonNode submitted = post("/support-works/" + workId + "/submissions", b, m("resultNote", "Đã làm xong phần việc khó",
				"evidence", List.of(m("type", "URL", "url", "https://github.com/x/y/pull/9", "label", "PR 9")), "actualMinutes", 240), 201);
		String taskSubId = submitted.at("/submission/taskSubmissionId").asString();
		review(g, taskSubId, "ACCEPTED", null, 409, null);

		// sau cutoff: TL duyệt → ĐÚNG MỘT AWARD cho Helper; gọi lặp (cùng/khác khóa) không cấp thêm
		runJob(Instant.parse("2026-11-20T16:00:00Z"));
		JsonNode ok = review(g, taskSubId, "ACCEPTED", null, 200, "key-aaaaaaaa-1");
		assertThat(ok.at("/award/recipient/userId").asString()).isEqualTo(b.id().toString());
		assertThat(ok.at("/award/points/pointUnits").asLong()).isEqualTo(100_000);
		review(g, taskSubId, "ACCEPTED", null, 200, "key-aaaaaaaa-1");
		review(g, taskSubId, "ACCEPTED", null, 200, "key-bbbbbbbb-2");
		assertThat(dbCount("SELECT count(*) FROM point_ledger_entries WHERE entry_type = 'AWARD'")).isEqualTo(1);
		assertThat(dbCount("SELECT count(*) FROM contributions WHERE status = 'CONFIRMED'")).isEqualTo(1);
		assertThat(get("/groups/" + g.id() + "/points/me", b).at("/confirmedPoints/pointUnits").asLong()).isEqualTo(100_000);
		assertThat(get("/groups/" + g.id() + "/points/me", a).at("/confirmedPoints/pointUnits").asLong()).isZero();
		assertThat(get("/support-works/" + workId, b).path("status").asString()).isEqualTo("APPROVED");

		// ---- Helper bị FIX_REQUIRED: work REVISION_REQUESTED + thông báo, carryover giữ Helper, nộp lại → đúng một AWARD nữa
		clock.set(Instant.parse("2026-11-21T03:00:00Z"));
		JsonNode hr2 = post("/groups/" + g.id() + "/help-requests", g.lead(), m("taskId", medium.id(), "beneficiaryId", a.id(),
				"title", "Làm thay task trung bình", "description", "Cần người làm thay toàn bộ task trung bình", "privacyChecked", true), 201);
		JsonNode offer2 = post("/help-requests/" + hr2.path("id").asString() + "/offers", b,
				m("acknowledgedScope", true, "helpRequestVersion", hr2.path("version").asLong()), 201);
		JsonNode accept2 = post("/help-requests/" + hr2.path("id").asString() + "/accept-offer", g.lead(),
				m("offerId", offer2.path("id").asString(), "version", get("/help-requests/" + hr2.path("id").asString(), g.lead()).path("version").asLong()), 200);
		String work2 = accept2.at("/work/id").asString();
		// vòng 1 của task trung bình đã quá hạn kỳ: Helper chờ TL chốt kỳ sau → carryover chưa có bản nộp
		JsonNode mediumCarry = post("/task-rounds/" + medium.roundId() + "/carryover", g.lead(),
				m("targetCycleId", k2.path("id").asString(), "deadline", "2026-11-24T10:00:00Z"), 201);
		assertThat(mediumCarry.at("/task/currentExecutor/userId").asString()).as("carryover giữ Helper").isEqualTo(b.id().toString());
		clock.set(Instant.parse("2026-11-23T03:00:00Z"));
		JsonNode s2 = post("/support-works/" + work2 + "/submissions", b, m("resultNote", "Bản đầu của task trung bình",
				"evidence", List.of(m("type", "URL", "url", "https://github.com/x/y/pull/10")), "actualMinutes", 90), 201);
		String taskSub2 = s2.at("/submission/taskSubmissionId").asString();
		assertThat(get("/tasks/" + medium.id(), b).at("/currentRound/state").asString()).isEqualTo("AWAITING_REVIEW");
		runJob(Instant.parse("2026-11-27T16:00:00Z")); // hết kỳ 2
		review(g, taskSub2, "FIX_REQUIRED", "Chưa xử lý trường hợp dữ liệu rỗng", 200, null);
		// BE-B1 gọi cổng của BE-B2 ngay trong transaction duyệt (chưa ai đọc work nên không phải "đồng bộ lười")
		assertThat(jdbc.sql("SELECT status FROM support_works WHERE id = :w").param("w", UUID.fromString(work2)).query(String.class).single())
				.isEqualTo("REVISION_REQUESTED");
		assertThat(count(b, "REVISION_REQUESTED")).isEqualTo(1);
		assertThat(get("/support-works/" + work2, b).path("status").asString()).isEqualTo("REVISION_REQUESTED");
		assertThat(dbCount("SELECT count(*) FROM point_ledger_entries WHERE entry_type = 'AWARD'")).isEqualTo(1);
		JsonNode k3 = get("/groups/" + g.id() + "/checkpoint-cycles?sort=asc&size=10", g.lead()).path("items").get(2);
		JsonNode carry2 = post("/task-rounds/" + mediumCarry.at("/targetRound/id").asString() + "/carryover", g.lead(),
				m("targetCycleId", k3.path("id").asString(), "deadline", "2026-12-02T10:00:00Z"), 201);
		assertThat(carry2.at("/task/currentExecutor/userId").asString()).isEqualTo(b.id().toString());
		assertThat(carry2.at("/targetRound/roundNo").asInt()).isEqualTo(3);
		clock.set(Instant.parse("2026-11-30T03:00:00Z"));
		JsonNode s3 = post("/support-works/" + work2 + "/submissions", b, m("resultNote", "Đã xử lý dữ liệu rỗng",
				"evidence", List.of(m("type", "URL", "url", "https://github.com/x/y/pull/10")), "actualMinutes", 30), 201);
		runJob(Instant.parse("2026-12-04T16:00:00Z"));
		JsonNode ok2 = review(g, s3.at("/submission/taskSubmissionId").asString(), "ACCEPTED", null, 200, null);
		assertThat(ok2.at("/award/recipient/userId").asString()).isEqualTo(b.id().toString());
		assertThat(ok2.at("/award/points/pointUnits").asLong()).isEqualTo(66_667); // P_t của task trung bình, không đổi qua 3 vòng
		assertThat(dbCount("SELECT count(*) FROM point_ledger_entries WHERE entry_type = 'AWARD'")).isEqualTo(2);
		assertThat(get("/groups/" + g.id() + "/points/me", b).at("/confirmedPoints/pointUnits").asLong()).isEqualTo(166_667);
	}

	// ------------------------------------------------------------------------------------------------ hạ tầng demo

	@Test
	void demoEndpointsDoNotExistOutsideDemoProfile() throws Exception {
		UserRef u = fx.user("Ai đó");
		call(HttpMethod.GET, "/demo/accounts", null, null, 404, null);
		call(HttpMethod.GET, "/demo/clock", u, null, 404, null);
		call(HttpMethod.POST, "/demo/clock", u, m("advanceMinutes", 5), 404, null);
	}
}
