package app.hanare.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.group.ApiHelper;
import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import app.hanare.testsupport.TestSupportConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Mục C: xem trước lịch, lưu lịch, danh sách/chi tiết kỳ, CycleService (C01: kỳ = (start, closes]). */
@HanareTest
class ScheduleApiTest {

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
	CycleService cycleService;

	@BeforeEach
	void resetClock() {
		fx.resetData();
		clock.set(Instant.parse(TestSupportConfig.DEFAULT_NOW));
	}

	/** Nhóm tạo qua API (thứ Sáu 22:00 giờ VN) + 2 thành viên. */
	private record Setup(UUID groupId, UserRef lead, UserRef an, UserRef binh) {
	}

	private Setup setup() throws Exception {
		UserRef lead = fx.user("Minh");
		JsonNode created = ApiHelper.createGroup(mvc, auth, lead, "Nhóm lịch", 5, "22:00");
		UUID gid = ApiHelper.id(created, "/group/id");
		UserRef an = fx.user("An");
		UserRef binh = fx.user("Bình");
		fx.addMember(gid, an, "MEMBER");
		fx.addMember(gid, binh, "MEMBER");
		return new Setup(gid, lead, an, binh);
	}

	private ResultActions preview(UserRef user, String json) throws Exception {
		return mvc.perform(post("/api/v1/checkpoint-schedules/preview").header("Authorization", auth.bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions save(UUID gid, UserRef user, String json) throws Exception {
		return mvc.perform(post("/api/v1/groups/" + gid + "/checkpoint-schedules")
				.header("Authorization", auth.bearer(user)).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String sched(int weekday, String time, String tz, String extra) {
		return "{\"weekday\":%d,\"cutoffTime\":\"%s\",\"timezone\":\"%s\"%s}".formatted(weekday, time, tz, extra);
	}

	// ------------------------------------------------------------------ preview
	@Test
	void previewForNewGroupShowsFridayExampleWithoutWritingAnything() throws Exception {
		UserRef minh = fx.user("Minh");
		preview(minh, sched(5, "22:00", "Asia/Ho_Chi_Minh", "")).andExpect(status().isOk())
				.andExpect(jsonPath("$.summaryLabel").value("Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00"))
				.andExpect(jsonPath("$.reminderBeforeMinutes").value(420))
				.andExpect(jsonPath("$.cycles.length()").value(4))
				.andExpect(jsonPath("$.cycles[0].sequenceNo").value(1))
				.andExpect(jsonPath("$.cycles[0].periodStartAt").value("2026-11-15T17:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].closesAt").value("2026-11-20T15:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].reminderAt").value("2026-11-20T08:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].periodHours").value(118))
				.andExpect(jsonPath("$.cycles[0].isTransition").value(true))
				.andExpect(jsonPath("$.cycles[1].sequenceNo").value(2))
				.andExpect(jsonPath("$.cycles[1].periodStartAt").value("2026-11-20T15:00:00Z"))
				.andExpect(jsonPath("$.cycles[1].periodHours").value(168))
				.andExpect(jsonPath("$.cycles[1].isTransition").value(false))
				.andExpect(jsonPath("$.cycles[1].label").value("Kỳ 2 · 20/11 22:00 → 27/11 22:00"))
				.andExpect(jsonPath("$.warnings.length()").value(0));
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_cycles").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_schedules").query(Integer.class).single()).isZero();
	}

	@Test
	void previewSundayGroupRemindsSunday1300AndHonoursStartDateAndWindow() throws Exception {
		UserRef minh = fx.user("Minh");
		preview(minh, sched(7, "20:00", "Asia/Ho_Chi_Minh", "").replace("}", ",\"startDate\":\"2026-11-23\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summaryLabel").value("Chốt Chủ nhật 20:00 → nhắc Chủ nhật 13:00"))
				.andExpect(jsonPath("$.cycles[0].periodStartAt").value("2026-11-22T17:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].closesAt").value("2026-11-29T13:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].reminderAt").value("2026-11-29T06:00:00Z"));
		preview(minh, sched(5, "22:00", "Asia/Ho_Chi_Minh", ",\"reportingWindowMinutes\":1440")).andExpect(status().isOk())
				.andExpect(jsonPath("$.cycles[1].opensAt").value("2026-11-26T15:00:00Z"))
				.andExpect(jsonPath("$.cycles[1].closesAt").value("2026-11-27T15:00:00Z"));
	}

	@Test
	void previewRejectsInvalidInputAndRequiresLogin() throws Exception {
		UserRef minh = fx.user("Minh");
		preview(minh, sched(9, "22:00", "Asia/Ho_Chi_Minh", "")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("SCHEDULE_INVALID"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("weekday"));
		preview(minh, sched(5, "22:00", "Asia/Ho_Chi_Minh", ",\"reportingWindowMinutes\":30"))
				.andExpect(status().isUnprocessableEntity());
		preview(minh, "{\"weekday\":5,\"timezone\":\"Asia/Ho_Chi_Minh\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/checkpoint-schedules/preview").contentType(MediaType.APPLICATION_JSON)
				.content(sched(5, "22:00", "Asia/Ho_Chi_Minh", ""))).andExpect(status().isUnauthorized());
	}

	@Test
	void previewForExistingGroupWarnsAboutShortTransitionCycleAndIsLeaderOnly() throws Exception {
		Setup s = setup();
		String sunday = sched(7, "20:00", "Asia/Ho_Chi_Minh", "").replace("{", "{\"groupId\":\"" + s.groupId() + "\",");
		preview(s.lead(), sunday).andExpect(status().isOk())
				.andExpect(jsonPath("$.summaryLabel").value("Chốt Chủ nhật 20:00 → nhắc Chủ nhật 13:00"))
				.andExpect(jsonPath("$.cycles[0].sequenceNo").value(2)) // kỳ 1 đang mở nên giữ nguyên
				.andExpect(jsonPath("$.cycles[0].periodStartAt").value("2026-11-20T15:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].closesAt").value("2026-11-22T13:00:00Z"))
				.andExpect(jsonPath("$.cycles[0].periodHours").value(46))
				.andExpect(jsonPath("$.cycles[0].isTransition").value(true))
				.andExpect(jsonPath("$.cycles[1].periodHours").value(168))
				.andExpect(jsonPath("$.warnings.length()").value(1))
				.andExpect(jsonPath("$.warnings[0].code").value("TRANSITION_CYCLE_SHORT"))
				.andExpect(jsonPath("$.warnings[0].message").value("Kỳ 2 chỉ dài 46 giờ vì đổi sang Chủ nhật 20:00."));
		// giữ nguyên lịch -> không có cảnh báo
		String same = sched(5, "22:00", "Asia/Ho_Chi_Minh", "").replace("{", "{\"groupId\":\"" + s.groupId() + "\",");
		preview(s.lead(), same).andExpect(status().isOk()).andExpect(jsonPath("$.warnings.length()").value(0))
				.andExpect(jsonPath("$.cycles[0].periodHours").value(168));
		preview(s.an(), sunday).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEAD_ONLY"));
		preview(fx.user("Người ngoài"), sunday).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_schedules").query(Integer.class).single()).isEqualTo(1);
	}

	// ------------------------------------------------------------------ save
	@Test
	void saveCreatesNewVersionRegeneratesOnlyUnopenedCyclesAndNotifiesWholeGroup() throws Exception {
		Setup s = setup();
		JsonNode res = ApiHelper.JSON.readTree(save(s.groupId(), s.lead(),
				sched(7, "20:00", "Asia/Ho_Chi_Minh", ",\"scheduleAgreed\":true")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.schedule.versionNo").value(2))
				.andExpect(jsonPath("$.schedule.weekday").value(7))
				.andExpect(jsonPath("$.schedule.cutoffTime").value("20:00"))
				.andExpect(jsonPath("$.schedule.effectiveFrom").value("2026-11-20T15:00:00Z"))
				.andExpect(jsonPath("$.schedule.agreedAt").value("2026-11-16T03:00:00Z"))
				.andExpect(jsonPath("$.schedule.recordedBy.userId").value(s.lead().id().toString()))
				.andExpect(jsonPath("$.schedule.isCurrent").value(true))
				.andExpect(jsonPath("$.schedule.summaryLabel").value("Chốt Chủ nhật 20:00 → nhắc Chủ nhật 13:00"))
				.andExpect(jsonPath("$.affectedCycles.length()").value(4))
				.andExpect(jsonPath("$.affectedCycles[0].sequenceNo").value(2))
				.andExpect(jsonPath("$.affectedCycles[0].status").value("SCHEDULED"))
				.andExpect(jsonPath("$.affectedCycles[0].periodStartAt").value("2026-11-20T15:00:00Z"))
				.andExpect(jsonPath("$.affectedCycles[0].closesAt").value("2026-11-22T13:00:00Z"))
				.andExpect(jsonPath("$.affectedCycles[0].reminderAt").value("2026-11-22T06:00:00Z"))
				.andExpect(jsonPath("$.affectedCycles[1].closesAt").value("2026-11-29T13:00:00Z"))
				.andReturn().getResponse().getContentAsString());
		String newScheduleId = res.at("/schedule/id").asString();

		// lịch sử phiên bản: mới nhất trước, đúng một bản "hiện hành"
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-schedules")
				.header("Authorization", auth.bearer(s.an()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].versionNo").value(2))
				.andExpect(jsonPath("$.items[0].isCurrent").value(true)).andExpect(jsonPath("$.items[1].versionNo").value(1))
				.andExpect(jsonPath("$.items[1].isCurrent").value(false))
				.andExpect(jsonPath("$.items[1].weekday").value(5));

		// kỳ 1 (đã mở) giữ lịch cũ; các kỳ sau nối liền không hở/chồng
		List<JsonNode> cycles = ApiHelper.getJson(mvc, auth.bearer(s.lead()),
				"/api/v1/groups/" + s.groupId() + "/checkpoint-cycles?sort=asc").get("items").valueStream().toList();
		assertThat(cycles).hasSize(5);
		assertThat(cycles.get(0).get("status").asString()).isEqualTo("OPEN");
		assertThat(cycles.get(0).get("closesAt").asString()).isEqualTo("2026-11-20T15:00:00Z");
		assertThat(cycles.get(0).get("scheduleId").asString()).isNotEqualTo(newScheduleId);
		for (int i = 1; i < cycles.size(); i++) {
			assertThat(cycles.get(i).get("periodStartAt").asString()).isEqualTo(cycles.get(i - 1).get("closesAt").asString());
			assertThat(cycles.get(i).get("scheduleId").asString()).isEqualTo(newScheduleId);
		}

		// CHECKPOINT_SCHEDULE_CHANGED tới cả nhóm (kể cả TL), đúng một sự kiện
		assertThat(jdbc.sql("""
				SELECT count(*) FROM notifications n JOIN notification_events e ON e.id = n.event_id
				 WHERE e.project_id = :p AND e.event_type = 'CHECKPOINT_SCHEDULE_CHANGED'
				""").param("p", s.groupId()).query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT dedup_key FROM notification_events WHERE event_type = 'CHECKPOINT_SCHEDULE_CHANGED'")
				.query(String.class).single()).isEqualTo("schedule:" + newScheduleId + ":changed");
	}

	@Test
	void cyclesAlreadyCarryingTasksKeepTheirBoundaries() throws Exception {
		Setup s = setup();
		List<JsonNode> before = ApiHelper.getJson(mvc, auth.bearer(s.lead()),
				"/api/v1/groups/" + s.groupId() + "/checkpoint-cycles?sort=asc").get("items").valueStream().toList();
		JsonNode third = before.get(2);
		DbFixtures.CycleRef ref = new DbFixtures.CycleRef(UUID.fromString(third.get("id").asString()), 3,
				Instant.parse(third.get("periodStartAt").asString()), Instant.parse(third.get("closesAt").asString()));
		fx.approvedTask(s.groupId(), s.lead().id(), s.an().id(), ref, "MEDIUM", ref.closesAt().minusSeconds(3600));

		save(s.groupId(), s.lead(), sched(7, "20:00", "Asia/Ho_Chi_Minh", ",\"scheduleAgreed\":true"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.schedule.effectiveFrom").value(ref.closesAt().toString()))
				.andExpect(jsonPath("$.affectedCycles[0].sequenceNo").value(4));
		List<JsonNode> after = ApiHelper.getJson(mvc, auth.bearer(s.lead()),
				"/api/v1/groups/" + s.groupId() + "/checkpoint-cycles?sort=asc").get("items").valueStream().toList();
		for (int i = 0; i < 3; i++) { // kỳ 1-3 giữ nguyên (kỳ 3 đã có task)
			assertThat(after.get(i).get("closesAt")).isEqualTo(before.get(i).get("closesAt"));
			assertThat(after.get(i).get("scheduleId")).isEqualTo(before.get(i).get("scheduleId"));
		}
		assertThat(after.get(3).get("periodStartAt").asString()).isEqualTo(ref.closesAt().toString());
		assertThat(after.get(3).get("closesAt").asString()).isEqualTo("2026-12-06T13:00:00Z"); // CN 06/12 20:00 VN, sau 04/12 22:00
		assertThat(after).hasSizeGreaterThanOrEqualTo(5);
	}

	@Test
	void saveTimezoneChangeKeepsGroupDatesAndOldCyclesTimezone() throws Exception {
		Setup s = setup();
		save(s.groupId(), s.lead(), sched(5, "22:00", "Asia/Tokyo", ",\"scheduleAgreed\":true")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.schedule.timezone").value("Asia/Tokyo"))
				.andExpect(jsonPath("$.affectedCycles[0].timezone").value("Asia/Tokyo"))
				.andExpect(jsonPath("$.affectedCycles[0].closesAt").value("2026-11-27T13:00:00Z")); // 22:00 JST
		mvc.perform(get("/api/v1/groups/" + s.groupId()).header("Authorization", auth.bearer(s.an())))
				.andExpect(jsonPath("$.timezone").value("Asia/Tokyo")).andExpect(jsonPath("$.startDate").value("2026-11-16"))
				.andExpect(jsonPath("$.deadline").value("2026-12-20"));
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-cycles?sort=asc")
				.header("Authorization", auth.bearer(s.an()))).andExpect(jsonPath("$.items[0].timezone").value("Asia/Ho_Chi_Minh"))
				.andExpect(jsonPath("$.items[1].timezone").value("Asia/Tokyo"));
	}

	@Test
	void saveIsLeaderOnlyAndValidated() throws Exception {
		Setup s = setup();
		String body = sched(7, "20:00", "Asia/Ho_Chi_Minh", ",\"scheduleAgreed\":true");
		save(s.groupId(), s.an(), body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEAD_ONLY"));
		save(s.groupId(), fx.user("Người ngoài"), body).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		save(s.groupId(), s.lead(), sched(7, "20:00", "Asia/Ho_Chi_Minh", ",\"scheduleAgreed\":false"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		save(s.groupId(), s.lead(), sched(7, "20:00", "Asia/Ho_Chi_Minh", "")).andExpect(status().isBadRequest());
		save(s.groupId(), s.lead(), sched(0, "20:00", "Asia/Ho_Chi_Minh", ",\"scheduleAgreed\":true"))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SCHEDULE_INVALID"));
		mvc.perform(post("/api/v1/groups/" + s.groupId() + "/checkpoint-schedules")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		// không có thay đổi nào được ghi
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_schedules WHERE project_id = :p").param("p", s.groupId())
				.query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM notification_events WHERE event_type = 'CHECKPOINT_SCHEDULE_CHANGED'")
				.query(Integer.class).single()).isZero();
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-schedules")
				.header("Authorization", auth.bearer(fx.user("Người ngoài")))).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ cycles
	@Test
	void listCyclesSupportsSortPagingAndStatusFilter() throws Exception {
		Setup s = setup();
		String url = "/api/v1/groups/" + s.groupId() + "/checkpoint-cycles";
		mvc.perform(get(url).header("Authorization", auth.bearer(s.an()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(5)).andExpect(jsonPath("$.items[0].sequenceNo").value(5))
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalItems").value(5)).andExpect(jsonPath("$.totalPages").value(1));
		mvc.perform(get(url + "?sort=asc&size=2&page=1").header("Authorization", auth.bearer(s.an())))
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].sequenceNo").value(3))
				.andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.page").value(1));
		mvc.perform(get(url + "?status=OPEN").header("Authorization", auth.bearer(s.an())))
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].sequenceNo").value(1))
				.andExpect(jsonPath("$.items[0].isCurrent").value(true))
				.andExpect(jsonPath("$.items[0].reportWindowOpen").value(true));
		mvc.perform(get(url + "?status=SCHEDULED").header("Authorization", auth.bearer(s.an())))
				.andExpect(jsonPath("$.totalItems").value(4)).andExpect(jsonPath("$.items[0].isCurrent").value(false))
				.andExpect(jsonPath("$.items[3].reportWindowOpen").value(false));
		mvc.perform(get(url + "?status=NOPE").header("Authorization", auth.bearer(s.an()))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get(url + "?size=1000").header("Authorization", auth.bearer(s.an()))).andExpect(status().isBadRequest());
		mvc.perform(get(url + "?sort=up").header("Authorization", auth.bearer(s.an()))).andExpect(status().isBadRequest());
		mvc.perform(get(url).header("Authorization", auth.bearer(fx.user("Người ngoài"))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		mvc.perform(get(url)).andExpect(status().isUnauthorized());
	}

	@Test
	void currentAndDetailCycleCarryMemberCountsAndNeighbours() throws Exception {
		Setup s = setup();
		JsonNode cycles = ApiHelper.getJson(mvc, auth.bearer(s.lead()),
				"/api/v1/groups/" + s.groupId() + "/checkpoint-cycles?sort=asc");
		UUID c1 = UUID.fromString(cycles.at("/items/0/id").asString());
		UUID c2 = UUID.fromString(cycles.at("/items/1/id").asString());
		// An đã gửi báo cáo kỳ 1 (đọc từ checkpoint_reports)
		UUID participant = jdbc.sql("""
				INSERT INTO checkpoint_participants (cycle_id, project_id, member_id) VALUES (:c, :p, :m) RETURNING id
				""").param("c", c1).param("p", s.groupId()).param("m", s.an().id()).query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO checkpoint_reports (participant_id, cycle_id, member_id, revision_no, status, submitted_at)
				VALUES (:pt, :c, :m, 1, 'SUBMITTED', :at)
				""").param("pt", participant).param("c", c1).param("m", s.an().id())
				.param("at", app.hanare.common.DbTime.toDb(Instant.parse("2026-11-16T02:00:00Z"))).update();

		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-cycles/current")
				.header("Authorization", auth.bearer(s.binh()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(c1.toString())).andExpect(jsonPath("$.serverTime").value("2026-11-16T03:00:00Z"))
				.andExpect(jsonPath("$.previousCycleId").value((Object) null))
				.andExpect(jsonPath("$.nextCycleId").value(c2.toString()))
				.andExpect(jsonPath("$.activeMemberCount").value(3)).andExpect(jsonPath("$.reportedMemberCount").value(1))
				.andExpect(jsonPath("$.notRespondedMemberCount").value(2)).andExpect(jsonPath("$.leadSummary").value((Object) null))
				.andExpect(jsonPath("$.isCurrent").value(true)).andExpect(jsonPath("$.status").value("OPEN"));
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-cycles/" + c2)
				.header("Authorization", auth.bearer(s.an()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.sequenceNo").value(2)).andExpect(jsonPath("$.previousCycleId").value(c1.toString()))
				.andExpect(jsonPath("$.isCurrent").value(false)).andExpect(jsonPath("$.reportedMemberCount").value(0))
				.andExpect(jsonPath("$.label").value("Kỳ 2 · 20/11 22:00 → 27/11 22:00"));

		// kỳ của nhóm khác / không tồn tại -> 404 NOT_FOUND; người ngoài -> 404 NOT_A_GROUP_MEMBER
		Setup other = setup();
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-cycles/"
				+ ApiHelper.getJson(mvc, auth.bearer(other.lead()), "/api/v1/groups/" + other.groupId()
						+ "/checkpoint-cycles/current").get("id").asString())
				.header("Authorization", auth.bearer(s.an()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-cycles/" + c2)
				.header("Authorization", auth.bearer(other.lead()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		// trước ngày bắt đầu chưa có kỳ nào chứa "bây giờ"
		clock.set(Instant.parse("2026-11-10T00:00:00Z"));
		mvc.perform(get("/api/v1/groups/" + s.groupId() + "/checkpoint-cycles/current")
				.header("Authorization", auth.bearer(s.an()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	// ------------------------------------------------------------------ CycleService (cổng chung)
	@Test
	void cycleServiceFollowsLeftOpenRightClosedBoundaries() throws Exception {
		Setup s = setup();
		UUID gid = s.groupId();
		CycleService.CycleRef c1 = cycleService.findCurrent(gid).orElseThrow();
		assertThat(c1.sequenceNo()).isEqualTo(1);
		Instant closes1 = Instant.parse("2026-11-20T15:00:00Z"); // 22:00 thứ Sáu 20/11 giờ VN
		// deadline đúng 22:00 thứ Sáu thuộc kỳ này (đúng MỘT kỳ)
		assertThat(cycleService.findContaining(gid, closes1)).map(CycleService.CycleRef::sequenceNo).contains(1);
		assertThat(cycleService.findContaining(gid, closes1.minusMillis(1))).map(CycleService.CycleRef::sequenceNo)
				.contains(1);
		// sau giờ chốt 1ms và Chủ nhật 22/11 22:00 giờ VN thuộc kỳ SAU
		assertThat(cycleService.findContaining(gid, closes1.plusMillis(1))).map(CycleService.CycleRef::sequenceNo).contains(2);
		assertThat(cycleService.findContaining(gid, Instant.parse("2026-11-22T15:00:00Z")))
				.map(CycleService.CycleRef::sequenceNo).contains(2);
		// cận dưới loại trừ: đúng periodStartAt của kỳ 1 không thuộc kỳ nào (không có kỳ trước)
		assertThat(cycleService.findContaining(gid, c1.periodStartAt())).isEmpty();
		assertThat(cycleService.findContaining(gid, Instant.parse("2027-12-31T00:00:00Z"))).isEmpty();
		assertThat(cycleService.findNext(gid, c1.id())).map(CycleService.CycleRef::sequenceNo).contains(2);
		assertThat(cycleService.findById(gid, c1.id())).isPresent();
		assertThat(cycleService.findById(UUID.randomUUID(), c1.id())).isEmpty();
		assertThat(c1.closesAt()).isEqualTo(closes1);
		assertThat(c1.status()).isEqualTo("OPEN");
	}
}
