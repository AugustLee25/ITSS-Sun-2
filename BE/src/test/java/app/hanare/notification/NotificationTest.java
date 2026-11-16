package app.hanare.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.group.ApiHelper;
import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.CycleRef;
import app.hanare.testsupport.DbFixtures.GroupRef;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import app.hanare.testsupport.TestSupportConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** Mục E + cài {@link NotificationService}: idempotent theo dedupKey, danh sách/đếm/đọc/mở, đích điều hướng. */
@HanareTest
class NotificationTest {

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
	NotificationService service;

	private GroupRef group;
	private UserRef lead;
	private UserRef m1;
	private UserRef m2;
	private List<CycleRef> cycles;
	private TaskRef task;

	@BeforeEach
	void setUp() {
		fx.resetData();
		clock.set(Instant.parse(TestSupportConfig.DEFAULT_NOW));
		group = fx.group("Nhóm TB", 3);
		lead = group.leader();
		m1 = group.members().get(0);
		m2 = group.members().get(1);
		UUID schedule = fx.schedule(group.projectId(), lead.id());
		cycles = fx.weeklyCycles(group.projectId(), schedule, 3);
		task = fx.approvedTask(group.projectId(), lead.id(), m1.id(), cycles.get(0), "EASY",
				cycles.get(0).closesAt().minusSeconds(3600));
	}

	private boolean publish(String type, String entityType, UUID entityId, UUID cycleId, String dedup,
			List<UUID> recipients, Map<String, Object> payload) {
		return service.publish(new NotificationEvent(group.projectId(), type, entityType, entityId, cycleId, dedup,
				recipients, "Tiêu đề " + type, "Nội dung " + type, payload));
	}

	private int count(String sql) {
		return jdbc.sql(sql).query(Integer.class).single();
	}

	// ------------------------------------------------------------------ NotificationService
	@Test
	void publishWritesEventOnceAndOnlyToActiveMembers() {
		UserRef stranger = fx.user("Người lạ");
		UserRef gone = fx.user("Đã rời");
		fx.addMember(group.projectId(), gone, "MEMBER");
		jdbc.sql("UPDATE project_members SET left_at = joined_at WHERE user_id = :u").param("u", gone.id()).update();

		List<UUID> recipients = List.of(lead.id(), m1.id(), m1.id(), stranger.id(), gone.id());
		assertThat(publish("CHECKPOINT_REMINDER", "CHECKPOINT_CYCLE", cycles.get(0).id(), cycles.get(0).id(),
				"checkpoint:" + cycles.get(0).id() + ":reminder", recipients, Map.of("cycleId", cycles.get(0).id().toString())))
				.isTrue();
		assertThat(count("SELECT count(*) FROM notification_events")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(2); // TL + m1, không trùng, bỏ người ngoài/đã rời
		assertThat(jdbc.sql("SELECT created_at FROM notifications LIMIT 1")
				.query((rs, i) -> app.hanare.common.DbTime.instant(rs, "created_at")).single())
				.isEqualTo(Instant.parse(TestSupportConfig.DEFAULT_NOW));

		// cùng dedupKey -> không ghi gì, trả false (kể cả khi đổi người nhận/nội dung)
		assertThat(publish("CHECKPOINT_REMINDER", "CHECKPOINT_CYCLE", cycles.get(0).id(), cycles.get(0).id(),
				"checkpoint:" + cycles.get(0).id() + ":reminder", List.of(m2.id()), Map.of())).isFalse();
		assertThat(count("SELECT count(*) FROM notification_events")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(2);

		// dedupKey khác -> sự kiện mới; danh sách rỗng -> chỉ ghi sự kiện
		assertThat(publish("PLAN_APPROVED", "PLAN_REVISION", task.planId(), null, "plan:" + task.planId() + ":approved",
				List.of(), null)).isTrue();
		assertThat(count("SELECT count(*) FROM notification_events")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(2);
	}

	@Test
	void publishRejectsProgrammerErrors() {
		assertThatThrownBy(() -> publish("NOT_A_TYPE", "TASK", UUID.randomUUID(), null, "k1", List.of(lead.id()), Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> publish("TASK_ASSIGNED", "WHAT", UUID.randomUUID(), null, "k2", List.of(lead.id()), Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> publish("TASK_ASSIGNED", "TASK", UUID.randomUUID(), null, " ", List.of(lead.id()), Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("SELECT count(*) FROM notification_events")).isZero();
	}

	@Test
	void concurrentPublishWithSameDedupKeyCreatesExactlyOneEvent() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				Callable<Boolean> c = () -> {
					go.await();
					return publish("TASK_ASSIGNED", "TASK", task.taskId(), null, "task:" + task.taskId() + ":assigned",
							List.of(m1.id()), Map.of("taskId", task.taskId().toString()));
				};
				results.add(pool.submit(c));
			}
			go.countDown();
			int created = 0;
			for (Future<Boolean> f : results) {
				created += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
			}
			assertThat(created).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM notification_events")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(1);
	}

	// ------------------------------------------------------------------ GET /notifications
	private void seedFour() {
		publish("CHECKPOINT_REMINDER", "CHECKPOINT_CYCLE", cycles.get(0).id(), cycles.get(0).id(), "d1", List.of(m1.id()),
				Map.of("subjectLabel", "Kỳ 1"));
		clock.advance(Duration.ofMinutes(1));
		publish("PLAN_APPROVED", "PLAN_REVISION", task.planId(), null, "d2", List.of(m1.id()),
				Map.of("taskId", task.taskId().toString(), "subjectLabel", "Task mẫu"));
		clock.advance(Duration.ofMinutes(1));
		publish("POINTS_AWARDED", "LEDGER_ENTRY", UUID.randomUUID(), null, "d3", List.of(m1.id()),
				Map.of("taskId", task.taskId().toString()));
		clock.advance(Duration.ofMinutes(1));
		publish("TASK_ASSIGNED", "TASK", task.taskId(), null, "d4", List.of(m1.id(), m2.id()), Map.of());
	}

	private JsonNode list(UserRef user, String query) throws Exception {
		return ApiHelper.getJson(mvc, auth.bearer(user), "/api/v1/notifications" + query);
	}

	private static List<String> types(JsonNode page) {
		return page.get("items").valueStream().map(n -> n.get("type").asString()).toList();
	}

	@Test
	void listIsUnreadFirstThenNewestAndFiltersByTabUnreadAndGroup() throws Exception {
		seedFour();
		JsonNode all = list(m1, "");
		assertThat(types(all)).containsExactly("TASK_ASSIGNED", "POINTS_AWARDED", "PLAN_APPROVED", "CHECKPOINT_REMINDER");
		assertThat(all.get("totalItems").asInt()).isEqualTo(4);
		assertThat(all.get("page").asInt()).isZero();
		assertThat(all.get("size").asInt()).isEqualTo(20);
		assertThat(all.get("totalPages").asInt()).isEqualTo(1);

		JsonNode reminder = all.at("/items/3");
		assertThat(reminder.get("priority").asString()).isEqualTo("ACTION_REQUIRED");
		assertThat(reminder.get("groupId").asString()).isEqualTo(group.projectId().toString());
		assertThat(reminder.get("groupName").asString()).isEqualTo("Nhóm TB");
		assertThat(reminder.get("title").asString()).isEqualTo("Tiêu đề CHECKPOINT_REMINDER");
		assertThat(reminder.get("subjectLabel").asString()).isEqualTo("Kỳ 1");
		assertThat(reminder.get("entityType").asString()).isEqualTo("CHECKPOINT_CYCLE");
		assertThat(reminder.get("entityId").asString()).isEqualTo(cycles.get(0).id().toString());
		assertThat(reminder.get("cycleId").asString()).isEqualTo(cycles.get(0).id().toString());
		assertThat(reminder.get("isRead").asBoolean()).isFalse();
		assertThat(reminder.get("readAt").isNull()).isTrue();
		assertThat(reminder.get("createdAt").asString()).isEqualTo("2026-11-16T03:00:00Z");
		assertThat(all.at("/items/0/subjectLabel").isNull()).isTrue();

		// đánh dấu một thông báo đã đọc -> xuống cuối danh sách
		String readId = all.at("/items/1/id").asString(); // POINTS_AWARDED
		mvc.perform(patch("/api/v1/notifications/" + readId + "/read").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk());
		assertThat(types(list(m1, ""))).containsExactly("TASK_ASSIGNED", "PLAN_APPROVED", "CHECKPOINT_REMINDER",
				"POINTS_AWARDED");
		assertThat(types(list(m1, "?tab=ACTION_REQUIRED"))).containsExactly("TASK_ASSIGNED", "CHECKPOINT_REMINDER");
		assertThat(types(list(m1, "?tab=INFO"))).containsExactly("PLAN_APPROVED", "POINTS_AWARDED");
		assertThat(types(list(m1, "?tab=ALL"))).hasSize(4);
		assertThat(types(list(m1, "?unreadOnly=true"))).containsExactly("TASK_ASSIGNED", "PLAN_APPROVED",
				"CHECKPOINT_REMINDER");
		assertThat(types(list(m1, "?tab=INFO&unreadOnly=true"))).containsExactly("PLAN_APPROVED");

		JsonNode page2 = list(m1, "?size=3&page=1");
		assertThat(types(page2)).containsExactly("POINTS_AWARDED");
		assertThat(page2.get("totalItems").asInt()).isEqualTo(4);
		assertThat(page2.get("totalPages").asInt()).isEqualTo(2);

		// chỉ thấy thông báo của chính mình
		assertThat(types(list(m2, ""))).containsExactly("TASK_ASSIGNED");
		assertThat(types(list(lead, ""))).isEmpty();

		// lọc theo nhóm
		GroupRef other = fx.group("Nhóm khác", 2);
		fx.addMember(other.projectId(), m1, "MEMBER");
		service.publish(new NotificationEvent(other.projectId(), "HELP_PUBLISHED", "HELP_REQUEST", UUID.randomUUID(), null,
				"d5", List.of(m1.id()), "Có việc cần hỗ trợ", "Task T · Khó · +10,0000 điểm % khi TL duyệt", Map.of()));
		assertThat(types(list(m1, "?groupId=" + other.projectId()))).containsExactly("HELP_PUBLISHED");
		assertThat(types(list(m1, "?groupId=" + group.projectId()))).hasSize(4);
		assertThat(types(list(m1, ""))).hasSize(5);
	}

	@Test
	void listValidatesQueryAndRequiresLogin() throws Exception {
		mvc.perform(get("/api/v1/notifications?tab=NOPE").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/notifications?size=500").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/notifications?page=-1").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/notifications?groupId=abc").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());
	}

	@Test
	void targetsFollowTheScreenTableOfTheContract() throws Exception {
		UUID helpId = UUID.randomUUID();
		UUID workId = UUID.randomUUID();
		UUID reqId = UUID.randomUUID();
		UUID ledgerId = UUID.randomUUID();
		UUID riskId = UUID.randomUUID();
		String g = group.projectId().toString();
		List<UUID> to = List.of(m1.id());
		publish("CHECKPOINT_REVIEW_REQUIRED", "CHECKPOINT_CYCLE", cycles.get(0).id(), cycles.get(0).id(), "t1", to, Map.of());
		publish("CHECKPOINT_SCHEDULE_CHANGED", "CHECKPOINT_CYCLE", cycles.get(1).id(), cycles.get(1).id(), "t2", to, Map.of());
		publish("PLAN_SUBMITTED", "PLAN_REVISION", task.planId(), null, "t3", to, Map.of("taskId", task.taskId().toString()));
		publish("NEED_HELP", "SUPPORT_REQUEST", reqId, null, "t4", to, Map.of());
		publish("SUPPORT_OFFERED", "HELP_REQUEST", helpId, null, "t5", to, Map.of());
		publish("SUPPORT_ACCEPTED", "SUPPORT_WORK", workId, null, "t6", to, Map.of());
		publish("SUPPORT_SUBMITTED", "SUPPORT_WORK", workId, null, "t7", to, Map.of());
		publish("POINTS_AWARDED", "LEDGER_ENTRY", ledgerId, null, "t8", to, Map.of("taskId", task.taskId().toString()));
		publish("POINT_ASSIGNMENT_CHANGED", "HELP_REQUEST", helpId, null, "t9", to, Map.of("taskId", task.taskId().toString()));
		publish("RISK_RELEVANT", "RISK_REPORT", riskId, null, "t10", to, Map.of("taskId", task.taskId().toString()));
		publish("POINT_POLICY_ACTIVATED", "POINT_POLICY", UUID.randomUUID(), null, "t11", to, Map.of());

		Map<String, JsonNode> byType = new java.util.HashMap<>();
		list(m1, "?size=100").get("items").forEach(n -> byType.put(n.get("type").asString(), n.get("target")));
		assertTarget(byType.get("CHECKPOINT_REVIEW_REQUIRED"), "S03", Map.of("groupId", g, "cycleId", cycles.get(0).id().toString()));
		assertTarget(byType.get("CHECKPOINT_SCHEDULE_CHANGED"), "S03", Map.of("groupId", g));
		assertTarget(byType.get("PLAN_SUBMITTED"), "S05",
				Map.of("groupId", g, "taskId", task.taskId().toString(), "planId", task.planId().toString()));
		assertTarget(byType.get("NEED_HELP"), "S07", Map.of("groupId", g, "supportRequestId", reqId.toString()));
		assertTarget(byType.get("SUPPORT_OFFERED"), "S09", Map.of("groupId", g, "helpRequestId", helpId.toString()));
		assertTarget(byType.get("SUPPORT_ACCEPTED"), "S10", Map.of("groupId", g, "workId", workId.toString()));
		assertTarget(byType.get("SUPPORT_SUBMITTED"), "S11", Map.of("groupId", g, "workId", workId.toString()));
		assertTarget(byType.get("POINTS_AWARDED"), "S12",
				Map.of("groupId", g, "ledgerEntryId", ledgerId.toString(), "taskId", task.taskId().toString()));
		assertTarget(byType.get("POINT_ASSIGNMENT_CHANGED"), "S12", Map.of("groupId", g, "taskId", task.taskId().toString()));
		assertTarget(byType.get("RISK_RELEVANT"), "S05", Map.of("groupId", g, "taskId", task.taskId().toString()));
		assertTarget(byType.get("POINT_POLICY_ACTIVATED"), "S12", Map.of("groupId", g));
	}

	private static void assertTarget(JsonNode target, String screen, Map<String, String> params) {
		assertThat(target.get("screen").asString()).isEqualTo(screen);
		Map<String, String> actual = new java.util.HashMap<>();
		target.get("params").properties().forEach(e -> actual.put(e.getKey(), e.getValue().asString()));
		assertThat(actual).isEqualTo(params);
	}

	// ------------------------------------------------------------------ unread / read / open / read-all
	@Test
	void unreadCountTotalsActionRequiredAndPerGroup() throws Exception {
		seedFour();
		GroupRef other = fx.group("Nhóm khác", 2);
		fx.addMember(other.projectId(), m1, "MEMBER");
		service.publish(new NotificationEvent(other.projectId(), "NEED_HELP", "SUPPORT_REQUEST", UUID.randomUUID(), null, "x1",
				List.of(m1.id()), "Có đề nghị", "Nội dung", Map.of()));
		mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.total").value(5))
				.andExpect(jsonPath("$.actionRequired").value(3)) // REMINDER + TASK_ASSIGNED + NEED_HELP
				.andExpect(jsonPath("$.byGroup.length()").value(2));
		mvc.perform(get("/api/v1/notifications/unread-count?groupId=" + group.projectId())
				.header("Authorization", auth.bearer(m1))).andExpect(jsonPath("$.total").value(4))
				.andExpect(jsonPath("$.actionRequired").value(2)).andExpect(jsonPath("$.byGroup.length()").value(1))
				.andExpect(jsonPath("$.byGroup[0].groupId").value(group.projectId().toString()))
				.andExpect(jsonPath("$.byGroup[0].count").value(4));
		mvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", auth.bearer(lead)))
				.andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.byGroup.length()").value(0));
	}

	@Test
	void markReadChangesOnlyReadStateIsRepeatableAndPrivate() throws Exception {
		seedFour();
		String id = list(m1, "").at("/items/0/id").asString();
		clock.advance(Duration.ofMinutes(10));
		mvc.perform(patch("/api/v1/notifications/" + id + "/read").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.isRead").value(true))
				.andExpect(jsonPath("$.readAt").value("2026-11-16T03:13:00Z"));
		clock.advance(Duration.ofMinutes(10)); // lặp lại không đổi readAt
		mvc.perform(patch("/api/v1/notifications/" + id + "/read").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.readAt").value("2026-11-16T03:13:00Z"));
		// của người khác / không tồn tại -> 404
		mvc.perform(patch("/api/v1/notifications/" + id + "/read").header("Authorization", auth.bearer(m2)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(patch("/api/v1/notifications/" + UUID.randomUUID() + "/read").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE read_at IS NOT NULL").query(Integer.class).single())
				.isEqualTo(1);
	}

	@Test
	void openMarksReadAndRechecksTheTarget() throws Exception {
		UUID missingTask = UUID.randomUUID();
		publish("TASK_ASSIGNED", "TASK", task.taskId(), null, "o1", List.of(m1.id()), Map.of());
		publish("TASK_REVIEWED", "TASK", missingTask, null, "o2", List.of(m1.id()), Map.of());
		JsonNode items = list(m1, "");
		String assigned = null;
		String gone = null;
		for (JsonNode n : items.get("items")) {
			if (n.get("type").asString().equals("TASK_ASSIGNED")) {
				assigned = n.get("id").asString();
			}
			else {
				gone = n.get("id").asString();
			}
		}
		mvc.perform(post("/api/v1/notifications/" + assigned + "/open").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
				.andExpect(jsonPath("$.notification.isRead").value(true))
				.andExpect(jsonPath("$.target.screen").value("S05"))
				.andExpect(jsonPath("$.target.params.taskId").value(task.taskId().toString()))
				.andExpect(jsonPath("$.unavailableMessage").value((Object) null))
				.andExpect(jsonPath("$.fallback").value((Object) null));
		mvc.perform(post("/api/v1/notifications/" + gone + "/open").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.notification.isRead").value(true))
				.andExpect(jsonPath("$.unavailableMessage").value("Nội dung này không còn khả dụng"))
				.andExpect(jsonPath("$.target.params.taskId").value(missingTask.toString()))
				.andExpect(jsonPath("$.fallback.screen").value("S04"))
				.andExpect(jsonPath("$.fallback.params.groupId").value(group.projectId().toString()));
		mvc.perform(post("/api/v1/notifications/" + assigned + "/open").header("Authorization", auth.bearer(m2)))
				.andExpect(status().isNotFound());
	}

	@Test
	void openNeverExposesAPrivateSupportRequestToOtherMembers() throws Exception {
		UUID requestId = jdbc.sql("""
				INSERT INTO support_requests (project_id, task_id, requester_id, obstacle_type, description)
				VALUES (:p, :t, :r, 'TIME', 'Nội dung riêng tư') RETURNING id
				""").param("p", group.projectId()).param("t", task.taskId()).param("r", m1.id()).query(UUID.class).single();
		publish("NEED_HELP", "SUPPORT_REQUEST", requestId, null, "s1", List.of(lead.id(), m1.id(), m2.id()), Map.of());
		for (UserRef u : List.of(lead, m1)) { // TL và người gửi mở được
			String id = list(u, "").at("/items/0/id").asString();
			mvc.perform(post("/api/v1/notifications/" + id + "/open").header("Authorization", auth.bearer(u)))
					.andExpect(jsonPath("$.available").value(true))
					.andExpect(jsonPath("$.target.screen").value("S07"))
					.andExpect(jsonPath("$.target.params.supportRequestId").value(requestId.toString()));
		}
		String id = list(m2, "").at("/items/0/id").asString(); // thành viên khác: không có quyền xem
		String body = mvc.perform(post("/api/v1/notifications/" + id + "/open").header("Authorization", auth.bearer(m2)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
				.andExpect(jsonPath("$.fallback.screen").value("S03")).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("Nội dung riêng tư");
	}

	@Test
	void readAllOnlyTouchesReadStateWithinFilters() throws Exception {
		seedFour();
		GroupRef other = fx.group("Nhóm khác", 2);
		fx.addMember(other.projectId(), m1, "MEMBER");
		service.publish(new NotificationEvent(other.projectId(), "NEED_HELP", "SUPPORT_REQUEST", UUID.randomUUID(), null, "x1",
				List.of(m1.id()), "Có đề nghị", "Nội dung", Map.of()));

		mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", auth.bearer(m1))
				.contentType(MediaType.APPLICATION_JSON).content("{\"tab\":\"INFO\",\"groupId\":\"" + group.projectId() + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2)); // PLAN_APPROVED + POINTS_AWARDED
		assertThat(types(list(m1, "?unreadOnly=true"))).containsExactlyInAnyOrder("TASK_ASSIGNED", "CHECKPOINT_REMINDER",
				"NEED_HELP");
		mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", auth.bearer(m1))
				.contentType(MediaType.APPLICATION_JSON).content("{\"groupId\":\"" + other.projectId() + "\"}"))
				.andExpect(jsonPath("$.updated").value(1));
		mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", auth.bearer(m1)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2)); // không body = tất cả
		mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", auth.bearer(m1)))
				.andExpect(jsonPath("$.updated").value(0));
		// thông báo của người khác không bị đụng tới
		assertThat(types(list(m2, "?unreadOnly=true"))).containsExactly("TASK_ASSIGNED");
		mvc.perform(post("/api/v1/notifications/read-all").header("Authorization", auth.bearer(m1))
				.contentType(MediaType.APPLICATION_JSON).content("{\"tab\":\"NOPE\"}")).andExpect(status().isBadRequest());
	}
}
