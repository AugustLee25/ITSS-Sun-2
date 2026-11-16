package app.hanare.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import app.hanare.testsupport.TestSupportConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Mục B: tạo nhóm, danh sách, chi tiết/sửa, thành viên, mã mời, tham gia. Giờ test: thứ Hai 16/11/2026 10:00 giờ VN. */
@HanareTest
class GroupTest {

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

	@BeforeEach
	void resetClock() {
		fx.resetData();
		clock.set(Instant.parse(TestSupportConfig.DEFAULT_NOW));
	}

	private JsonNode create(UserRef lead, String name) throws Exception {
		return ApiHelper.createGroup(mvc, auth, lead, name, 5, "22:00");
	}

	private UUID groupId(JsonNode created) {
		return ApiHelper.id(created, "/group/id");
	}

	// ------------------------------------------------------------------ tạo nhóm
	@Test
	void createGroupReturnsContractShapeAndSeedsScheduleCyclesAndInvite() throws Exception {
		UserRef minh = fx.user("Minh");
		JsonNode res = ApiHelper.JSON.readTree(mvc.perform(post("/api/v1/groups")
				.header("Authorization", auth.bearer(minh)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"  Bài tập lớn CSDL ","courseName":"Cơ sở dữ liệu","deadline":"2026-12-20",
						 "schedule":{"weekday":5,"cutoffTime":"22:00","timezone":"Asia/Ho_Chi_Minh"},"scheduleAgreed":true}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.group.name").value("Bài tập lớn CSDL"))
				.andExpect(jsonPath("$.group.courseName").value("Cơ sở dữ liệu"))
				.andExpect(jsonPath("$.group.startDate").value("2026-11-16"))
				.andExpect(jsonPath("$.group.deadline").value("2026-12-20"))
				.andExpect(jsonPath("$.group.timezone").value("Asia/Ho_Chi_Minh"))
				.andExpect(jsonPath("$.group.memberCount").value(1))
				.andExpect(jsonPath("$.group.leader.userId").value(minh.id().toString()))
				.andExpect(jsonPath("$.group.leader.name").value("Minh"))
				.andExpect(jsonPath("$.group.leader.email").doesNotExist())
				.andExpect(jsonPath("$.group.myRole").value("LEAD"))
				.andExpect(jsonPath("$.group.createdAt").value("2026-11-16T03:00:00Z"))
				.andExpect(jsonPath("$.group.version").value(0))
				.andExpect(jsonPath("$.schedule.versionNo").value(1))
				.andExpect(jsonPath("$.schedule.weekday").value(5))
				.andExpect(jsonPath("$.schedule.cutoffTime").value("22:00"))
				.andExpect(jsonPath("$.schedule.reminderBeforeMinutes").value(420))
				.andExpect(jsonPath("$.schedule.reportingWindowMinutes").value((Object) null))
				.andExpect(jsonPath("$.schedule.effectiveFrom").value("2026-11-15T17:00:00Z"))
				.andExpect(jsonPath("$.schedule.agreedAt").value("2026-11-16T03:00:00Z"))
				.andExpect(jsonPath("$.schedule.recordedBy.userId").value(minh.id().toString()))
				.andExpect(jsonPath("$.schedule.isCurrent").value(true))
				.andExpect(jsonPath("$.schedule.summaryLabel").value("Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00"))
				.andExpect(jsonPath("$.currentCycle.sequenceNo").value(1))
				.andExpect(jsonPath("$.currentCycle.periodStartAt").value("2026-11-15T17:00:00Z"))
				.andExpect(jsonPath("$.currentCycle.opensAt").value("2026-11-15T17:00:00Z"))
				.andExpect(jsonPath("$.currentCycle.reminderAt").value("2026-11-20T08:00:00Z"))
				.andExpect(jsonPath("$.currentCycle.closesAt").value("2026-11-20T15:00:00Z"))
				.andExpect(jsonPath("$.currentCycle.status").value("OPEN"))
				.andExpect(jsonPath("$.currentCycle.phase").value("TENTATIVE"))
				.andExpect(jsonPath("$.currentCycle.phaseLabel").value("Tạm tính"))
				.andExpect(jsonPath("$.currentCycle.finalizedAt").value((Object) null))
				.andExpect(jsonPath("$.currentCycle.isCurrent").value(true))
				.andExpect(jsonPath("$.currentCycle.reportWindowOpen").value(true))
				.andExpect(jsonPath("$.currentCycle.label").value("Kỳ 1 · 16/11 00:00 → 20/11 22:00"))
				.andExpect(jsonPath("$.invite.status").value("ACTIVE"))
				.andExpect(jsonPath("$.invite.createdBy.userId").value(minh.id().toString()))
				.andExpect(jsonPath("$.invite.expiresAt").value("2026-11-30T03:00:00Z"))
				.andReturn().getResponse().getContentAsString());

		String code = res.at("/invite/code").asString();
		assertThat(code).hasSize(8).matches("[A-Z0-9]+");
		assertThat(res.at("/invite/sharePath").asString()).isEqualTo("/join/" + code);

		UUID gid = groupId(res);
		// 5 kỳ liền nhau: kỳ hiện tại + 4 kỳ tương lai, mỗi kỳ 7 ngày
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_cycles WHERE project_id = :p").param("p", gid)
				.query(Integer.class).single()).isEqualTo(5);
		assertThat(jdbc.sql("SELECT status FROM checkpoint_cycles WHERE project_id = :p ORDER BY sequence_no")
				.param("p", gid).query(String.class).list())
				.containsExactly("OPEN", "SCHEDULED", "SCHEDULED", "SCHEDULED", "SCHEDULED");
		// deadline nhóm hết hiệu lực 23:59:59.999 ngày 20/12 giờ VN
		assertThat(jdbc.sql("SELECT to_char(deadline AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS') FROM projects WHERE id = :p")
				.param("p", gid).query(String.class).single()).isEqualTo("2026-12-20T16:59:59.999");
		assertThat(jdbc.sql("SELECT role FROM project_members WHERE project_id = :p AND user_id = :u")
				.param("p", gid).param("u", minh.id()).query(String.class).single()).isEqualTo("LEAD");
	}

	@Test
	void createGroupValidatesInputAndBusinessRules() throws Exception {
		UserRef minh = fx.user("Minh");
		String bearer = auth.bearer(minh);
		// tên quá ngắn + thiếu xác nhận lịch -> 400
		mvc.perform(post("/api/v1/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"ab","deadline":"2026-12-20",
						 "schedule":{"weekday":5,"cutoffTime":"22:00","timezone":"Asia/Ho_Chi_Minh"},"scheduleAgreed":false}
						"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='name')]").exists());
		mvc.perform(post("/api/v1/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content(ApiHelper.groupBody("Nhóm hợp lệ", "2026-12-20", 5, "22:00", "Asia/Ho_Chi_Minh")
						.replace("\"scheduleAgreed\":true", "\"scheduleAgreed\":false")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='scheduleAgreed')]").exists());
		// thiếu deadline -> 400
		mvc.perform(post("/api/v1/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Nhóm hợp lệ","schedule":{"weekday":5,"cutoffTime":"22:00","timezone":"Asia/Ho_Chi_Minh"},"scheduleAgreed":true}
						"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[?(@.field=='deadline')]").exists());
		// startDate trước hôm nay -> 400
		mvc.perform(post("/api/v1/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Nhóm hợp lệ","startDate":"2026-11-15","deadline":"2026-12-20",
						 "schedule":{"weekday":5,"cutoffTime":"22:00","timezone":"Asia/Ho_Chi_Minh"},"scheduleAgreed":true}
						"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[?(@.field=='startDate')]").exists());
		// deadline trước startDate -> 422 GROUP_DEADLINE_INVALID
		mvc.perform(post("/api/v1/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Nhóm hợp lệ","startDate":"2026-11-20","deadline":"2026-11-19",
						 "schedule":{"weekday":5,"cutoffTime":"22:00","timezone":"Asia/Ho_Chi_Minh"},"scheduleAgreed":true}
						"""))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GROUP_DEADLINE_INVALID"));
		// lịch sai -> 422 SCHEDULE_INVALID (weekday, giờ, múi giờ, khung mở)
		mvc.perform(post("/api/v1/groups").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Nhóm hợp lệ","deadline":"2026-12-20",
						 "schedule":{"weekday":8,"cutoffTime":"25:61","timezone":"Mars/Olympus","reportingWindowMinutes":10},
						 "scheduleAgreed":true}
						"""))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SCHEDULE_INVALID"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(4))
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='schedule.weekday')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='schedule.cutoffTime')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='schedule.timezone')]").exists())
				.andExpect(jsonPath("$.fieldErrors[?(@.field=='schedule.reportingWindowMinutes')]").exists());
		assertThat(jdbc.sql("SELECT count(*) FROM projects").query(Integer.class).single()).isZero();
		mvc.perform(post("/api/v1/groups").contentType(MediaType.APPLICATION_JSON)
				.content(ApiHelper.groupBody("Nhóm hợp lệ", "2026-12-20", 5, "22:00", "Asia/Ho_Chi_Minh")))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ danh sách nhóm của tôi
	@Test
	void listsMyGroupsNewestActivityFirstWithAttentionLabels() throws Exception {
		UserRef minh = fx.user("Minh");
		UserRef an = fx.user("An");
		JsonNode first = create(minh, "Nhóm cũ");
		clock.advance(Duration.ofMinutes(5));
		JsonNode second = create(minh, "Nhóm mới");
		clock.advance(Duration.ofMinutes(1));
		// An vào nhóm cũ qua mã mời
		mvc.perform(post("/api/v1/invites/" + first.at("/invite/code").asString() + "/accept")
				.header("Authorization", auth.bearer(an))).andExpect(status().isOk());

		mvc.perform(get("/api/v1/groups").header("Authorization", auth.bearer(minh))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(groupId(first).toString())) // An vừa vào nhóm cũ = hoạt động mới nhất
				.andExpect(jsonPath("$.items[0].name").value("Nhóm cũ"))
				.andExpect(jsonPath("$.items[0].memberCount").value(2))
				.andExpect(jsonPath("$.items[0].deadline").value("2026-12-20"))
				.andExpect(jsonPath("$.items[0].myRole").value("LEAD"))
				.andExpect(jsonPath("$.items[0].attention.count").value(0))
				.andExpect(jsonPath("$.items[0].attention.label").value("Không có việc cần chú ý"))
				.andExpect(jsonPath("$.items[0].unreadNotifications").value(0))
				.andExpect(jsonPath("$.items[1].id").value(groupId(second).toString()));

		mvc.perform(get("/api/v1/groups").header("Authorization", auth.bearer(an))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].myRole").value("MEMBER"));
		mvc.perform(get("/api/v1/groups").header("Authorization", auth.bearer(fx.user("Cô đơn"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void groupCardAsksForCheckpointAfterReminderUntilReportSubmitted() throws Exception {
		UserRef minh = fx.user("Minh");
		create(minh, "Nhóm checkpoint");
		clock.set(Instant.parse("2026-11-20T08:30:00Z")); // sau nhắc 15:00 VN, trước chốt 22:00 VN
		mvc.perform(get("/api/v1/groups").header("Authorization", auth.bearer(minh))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].attention.count").value(1))
				.andExpect(jsonPath("$.items[0].attention.label").value("1 checkpoint cần phản hồi"));
		clock.set(Instant.parse("2026-11-20T16:00:00Z")); // sau chốt: không còn "cần phản hồi"
		mvc.perform(get("/api/v1/groups").header("Authorization", auth.bearer(minh)))
				.andExpect(jsonPath("$.items[0].attention.label").value("Không có việc cần chú ý"));
	}

	// ------------------------------------------------------------------ chi tiết / sửa / thành viên
	@Test
	void groupDetailVisibleToMembersOnly() throws Exception {
		UserRef minh = fx.user("Minh");
		UUID gid = groupId(create(minh, "Nhóm riêng"));
		mvc.perform(get("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(gid.toString())).andExpect(jsonPath("$.myRole").value("LEAD"));
		mvc.perform(get("/api/v1/groups/" + gid).header("Authorization", auth.bearer(fx.user("Người ngoài"))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		mvc.perform(get("/api/v1/groups/" + UUID.randomUUID()).header("Authorization", auth.bearer(minh)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
		mvc.perform(get("/api/v1/groups/" + gid)).andExpect(status().isUnauthorized());
	}

	@Test
	void leaderEditsGroupWithOptimisticVersion() throws Exception {
		UserRef minh = fx.user("Minh");
		UserRef an = fx.user("An");
		JsonNode created = create(minh, "Nhóm sửa");
		UUID gid = groupId(created);
		mvc.perform(post("/api/v1/invites/" + created.at("/invite/code").asString() + "/accept")
				.header("Authorization", auth.bearer(an))).andExpect(status().isOk());

		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Tên mới\",\"courseName\":\"Môn mới\",\"deadline\":\"2026-12-31\",\"version\":0}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tên mới"))
				.andExpect(jsonPath("$.courseName").value("Môn mới")).andExpect(jsonPath("$.deadline").value("2026-12-31"))
				.andExpect(jsonPath("$.version").value(1));
		// không gửi courseName = giữ nguyên; courseName null = xóa
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tên khác\",\"version\":1}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.courseName").value("Môn mới"))
				.andExpect(jsonPath("$.version").value(2));
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))
				.contentType(MediaType.APPLICATION_JSON).content("{\"courseName\":null,\"version\":2}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.courseName").value((Object) null))
				.andExpect(jsonPath("$.name").value("Tên khác"));
		// version cũ -> 409
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Lại sửa\",\"version\":0}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		// thành viên thường -> 403; deadline trước ngày bắt đầu -> 422; thiếu version/tên sai -> 400
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(an))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Tôi sửa\",\"version\":3}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEAD_ONLY"));
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))
				.contentType(MediaType.APPLICATION_JSON).content("{\"deadline\":\"2026-11-15\",\"version\":3}"))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GROUP_DEADLINE_INVALID"));
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(minh))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.length()").value(2));
		mvc.perform(patch("/api/v1/groups/" + gid).header("Authorization", auth.bearer(fx.user("Người ngoài")))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Xâm nhập\",\"version\":3}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));
	}

	@Test
	void membersListsLeaderFirstThenByJoinTimeWithoutEmails() throws Exception {
		UserRef minh = fx.user("Minh");
		UserRef an = fx.user("An");
		UserRef binh = fx.user("Bình");
		JsonNode created = create(minh, "Nhóm đông");
		String code = created.at("/invite/code").asString();
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(binh)))
				.andExpect(status().isOk());
		clock.advance(Duration.ofMinutes(1));
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(an)))
				.andExpect(status().isOk());

		String body = mvc.perform(get("/api/v1/groups/" + groupId(created) + "/members")
				.header("Authorization", auth.bearer(an))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[0].userId").value(minh.id().toString()))
				.andExpect(jsonPath("$.items[0].role").value("LEAD"))
				.andExpect(jsonPath("$.items[1].userId").value(binh.id().toString()))
				.andExpect(jsonPath("$.items[1].role").value("MEMBER"))
				.andExpect(jsonPath("$.items[2].userId").value(an.id().toString()))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("email").doesNotContain("@");
		mvc.perform(get("/api/v1/groups/" + groupId(created) + "/members")
				.header("Authorization", auth.bearer(fx.user("Người ngoài")))).andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ mã mời
	@Test
	void inviteLifecycleIsLeaderOnly() throws Exception {
		UserRef minh = fx.user("Minh");
		UserRef an = fx.user("An");
		JsonNode created = create(minh, "Nhóm mời");
		UUID gid = groupId(created);
		String oldCode = created.at("/invite/code").asString();
		mvc.perform(post("/api/v1/invites/" + oldCode + "/accept").header("Authorization", auth.bearer(an)))
				.andExpect(status().isOk());

		mvc.perform(get("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(minh)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.code").value(oldCode))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		// Member: 403; người ngoài: 404
		mvc.perform(get("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(an)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEAD_ONLY"));
		mvc.perform(post("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(an)))
				.andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(an)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(fx.user("Người ngoài"))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_A_GROUP_MEMBER"));

		// tạo mã mới: thu hồi mã cũ
		clock.advance(Duration.ofMinutes(1));
		String newCode = ApiHelper.JSON.readTree(mvc.perform(post("/api/v1/groups/" + gid + "/invite")
				.header("Authorization", auth.bearer(minh))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ACTIVE")).andReturn().getResponse().getContentAsString())
				.get("code").asString();
		assertThat(newCode).isNotEqualTo(oldCode);
		mvc.perform(get("/api/v1/invites/" + oldCode)).andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("INVITE_REVOKED"));
		mvc.perform(get("/api/v1/invites/" + newCode)).andExpect(status().isOk());

		// thu hồi: 204, sau đó GET/DELETE -> 404 INVITE_NOT_FOUND
		mvc.perform(delete("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(minh)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(minh)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		mvc.perform(delete("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(minh)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		mvc.perform(get("/api/v1/invites/" + newCode)).andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("INVITE_REVOKED"));
	}

	@Test
	void invitePreviewIsPublicAndReportsMembershipWhenTokenGiven() throws Exception {
		UserRef minh = fx.user("Minh");
		UserRef an = fx.user("An");
		JsonNode created = create(minh, "Nhóm xem trước");
		UUID gid = groupId(created);
		String code = created.at("/invite/code").asString();

		mvc.perform(get("/api/v1/invites/" + code.toLowerCase())).andExpect(status().isOk()) // không phân biệt hoa/thường
				.andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.groupName").value("Nhóm xem trước"))
				.andExpect(jsonPath("$.leader.name").value("Minh"))
				.andExpect(jsonPath("$.leader.email").doesNotExist())
				.andExpect(jsonPath("$.memberCount").value(1))
				.andExpect(jsonPath("$.expiresAt").value("2026-11-30T03:00:00Z"))
				.andExpect(jsonPath("$.alreadyMember").value((Object) null))
				.andExpect(jsonPath("$.groupId").value((Object) null));
		mvc.perform(get("/api/v1/invites/" + code).header("Authorization", auth.bearer(an)))
				.andExpect(jsonPath("$.alreadyMember").value(false)).andExpect(jsonPath("$.groupId").value((Object) null));
		mvc.perform(get("/api/v1/invites/" + code).header("Authorization", auth.bearer(minh)))
				.andExpect(jsonPath("$.alreadyMember").value(true)).andExpect(jsonPath("$.groupId").value(gid.toString()));

		mvc.perform(get("/api/v1/invites/ZZZZZZZZ")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		mvc.perform(get("/api/v1/invites/!!")).andExpect(status().isNotFound());
		// hết hạn sau 14 ngày
		clock.advance(Duration.ofDays(14));
		mvc.perform(get("/api/v1/invites/" + code)).andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
		mvc.perform(get("/api/v1/groups/" + gid + "/invite").header("Authorization", auth.bearer(minh)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
	}

	@Test
	void acceptInviteMakesCallerAMemberOnceAndRespectsExpiryAndRevocation() throws Exception {
		UserRef minh = fx.user("Minh");
		UserRef an = fx.user("An");
		JsonNode created = create(minh, "Nhóm tham gia");
		UUID gid = groupId(created);
		String code = created.at("/invite/code").asString();

		mvc.perform(post("/api/v1/invites/" + code + "/accept")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(an)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.group.id").value(gid.toString()))
				.andExpect(jsonPath("$.group.myRole").value("MEMBER")).andExpect(jsonPath("$.group.memberCount").value(2))
				.andExpect(jsonPath("$.group.leader.name").value("Minh"));
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(an)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(minh)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
		mvc.perform(post("/api/v1/invites/NOPE1234/accept").header("Authorization", auth.bearer(an)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"));
		// thành viên mới thấy được nhóm
		mvc.perform(get("/api/v1/groups/" + gid).header("Authorization", auth.bearer(an))).andExpect(status().isOk());

		// người từng rời nhóm vào lại được bằng cùng mã
		jdbc.sql("UPDATE project_members SET left_at = joined_at WHERE project_id = :p AND user_id = :u")
				.param("p", gid).param("u", an.id()).update();
		mvc.perform(get("/api/v1/groups/" + gid).header("Authorization", auth.bearer(an))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(an)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.group.memberCount").value(2));

		UserRef binh = fx.user("Bình");
		clock.advance(Duration.ofDays(15));
		mvc.perform(post("/api/v1/invites/" + code + "/accept").header("Authorization", auth.bearer(binh)))
				.andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
		assertThat(jdbc.sql("SELECT count(*) FROM project_members WHERE project_id = :p AND user_id = :u")
				.param("p", gid).param("u", binh.id()).query(Integer.class).single()).isZero();
	}
}
