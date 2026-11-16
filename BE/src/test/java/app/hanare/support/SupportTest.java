package app.hanare.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.hanare.points.ApiTestBase;
import app.hanare.points.RecordingNotifications;
import app.hanare.testsupport.HanareTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** I: đề nghị hỗ trợ riêng + risk — quyền riêng tư (người gửi + TL), thông báo không rò rỉ, DROPOUT_RISK chỉ TL xác nhận. */
@HanareTest
@Import(RecordingNotifications.Config.class)
class SupportTest extends ApiTestBase {

	private static final String SECRET = "BÍ MẬT hoàn cảnh gia đình riêng tư";

	private String createJson() {
		return "{\"obstacleType\":\"PERSONAL\",\"description\":\"" + SECRET + " khiến mình không kịp làm\","
				+ "\"neededSupport\":\"Cần thêm thời gian hoặc người hỗ trợ\",\"impact\":\"Có thể trễ 1 ngày\","
				+ "\"expectedDate\":\"2026-11-21\"}";
	}

	private String url(World w, String name) {
		return "/api/v1/tasks/" + w.t(name).taskId() + "/support-requests";
	}

	private UUID createRequest(World w) throws Exception {
		return uuid(postJson(w.a(), url(w, "a1"), createJson(), 201), "$.id");
	}

	@Test
	void assigneeSendsPrivateRequestAndOnlyTheLeadIsNotifiedWithoutPrivateContent() throws Exception {
		World w = world();
		MvcResult r = postJson(w.a(), url(w, "a1"), createJson(), 201);
		assertThat((String) at(r, "$.status")).isEqualTo("OPEN");
		assertThat((String) at(r, "$.sender.name")).isEqualTo(w.a().name());
		assertThat((String) at(r, "$.groupId")).isEqualTo(w.pid().toString());
		assertThat((String) at(r, "$.roundId")).isEqualTo(w.t("a1").roundId().toString());
		assertThat((String) at(r, "$.expectedDate")).isEqualTo("2026-11-21");
		assertThat((String) at(r, "$.neededSupport")).contains("thêm thời gian");
		assertThat((Object) at(r, "$.decision")).isNull();
		assertThat((Integer) at(r, "$.version")).isZero();
		assertThat((Integer) at(r, "$.allowedActions.length()")).isZero();
		assertThat(notes.ofType("NEED_HELP")).hasSize(1);
		assertThat(notes.ofType("NEED_HELP").get(0).recipientIds()).containsExactly(w.lead().id());
		assertThat(notes.allText()).doesNotContain(SECRET).doesNotContain("PERSONAL").doesNotContain("trễ 1 ngày");
		assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE project_id = :p1 AND entity_type = 'SUPPORT_REQUEST'", w.pid()))
				.isEqualTo(1);
	}

	@Test
	void sendingIsLimitedToTheCurrentAssigneeAndValidated() throws Exception {
		World w = world();
		assertThat(errorCode(postJson(w.lead(), url(w, "l1"), createJson(), 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		assertThat(errorCode(postJson(w.lead(), url(w, "a1"), createJson(), 403))).isEqualTo("SELF_ACTION_FORBIDDEN");
		assertThat(errorCode(postJson(w.b(), url(w, "a1"), createJson(), 403))).isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postJson(fx.user("Người ngoài"), url(w, "a1"), createJson(), 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(postJson(w.a(), "/api/v1/tasks/" + UUID.randomUUID() + "/support-requests", createJson(), 404)))
				.isEqualTo("NOT_FOUND");
		call(null, post(url(w, "a1")).contentType(MediaType.APPLICATION_JSON).content(createJson()), 401);
		MvcResult shortDesc = postJson(w.a(), url(w, "a1"),
				"{\"obstacleType\":\"TIME\",\"description\":\"ngắn\",\"neededSupport\":\"Cần thêm thời gian\"}", 400);
		assertThat(errorCode(shortDesc)).isEqualTo("VALIDATION_FAILED");
		List<String> fields = JsonPath.read(body(shortDesc), "$.fieldErrors[*].field");
		assertThat(fields).contains("description");
		assertThat(errorCode(postJson(w.a(), url(w, "a1"),
				"{\"obstacleType\":\"NOPE\",\"description\":\"đủ dài để hợp lệ\",\"neededSupport\":\"Cần thêm thời gian\"}", 400)))
				.isEqualTo("MALFORMED_REQUEST");
		String foreignRound = createJson().replace("{", "{\"roundId\":\"" + w.t("b1").roundId() + "\",");
		assertThat(errorCode(postJson(w.a(), url(w, "a1"), foreignRound, 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(count("SELECT COUNT(*) FROM support_requests WHERE project_id = :p1", w.pid())).isZero();
		assertThat(notes.ofType("NEED_HELP")).isEmpty();
	}

	@Test
	void requestIsReadableOnlyBySenderAndLeadAndNeverLeaksElsewhere() throws Exception {
		World w = world();
		UUID id = createRequest(w);
		String path = "/api/v1/support-requests/" + id;
		MvcResult own = getOk(w.a(), path);
		assertThat((String) at(own, "$.description")).contains(SECRET);
		MvcResult lead = getOk(w.lead(), path);
		List<String> actions = JsonPath.read(body(lead), "$.allowedActions");
		assertThat(actions).containsExactly("DECIDE", "PUBLISH_HELP");
		assertThat(errorCode(getStatus(w.b(), path, 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(getStatus(w.d(), path, 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(getStatus(fx.user("Người ngoài"), path, 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(getStatus(w.a(), "/api/v1/support-requests/" + UUID.randomUUID(), 404))).isEqualTo("NOT_FOUND");

		String list = "/api/v1/groups/" + w.pid() + "/support-requests";
		assertThat((Integer) at(getOk(w.a(), list), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(getOk(w.b(), list), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.b(), list + "?scope=ALL"), "$.totalItems")).isZero(); // Member bị ép MINE
		MvcResult leadList = getOk(w.lead(), list);
		assertThat((Integer) at(leadList, "$.totalItems")).isEqualTo(1);
		assertThat((String) at(leadList, "$.items[0].taskTitle")).startsWith("Task EASY");
		assertThat(body(leadList)).doesNotContain(SECRET); // tóm tắt không chứa nội dung riêng
		assertThat((Integer) at(getOk(w.lead(), list + "?scope=MINE"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.lead(), list + "?status=RESOLVED"), "$.totalItems")).isZero();
		assertThat((Integer) at(getOk(w.lead(), list + "?taskId=" + w.t("a1").taskId()), "$.totalItems")).isEqualTo(1);
		assertThat(errorCode(getStatus(w.lead(), list + "?status=NOPE", 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(getStatus(fx.user("Người ngoài"), list, 404))).isEqualTo("NOT_A_GROUP_MEMBER");

		// rò rỉ qua các bề mặt công khai khác của nhóm: risk-reports, help-requests, điểm
		assertThat(body(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/risk-reports"))).doesNotContain(SECRET);
		assertThat(body(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/help-requests"))).doesNotContain(SECRET);
		assertThat(body(getOk(w.b(), "/api/v1/groups/" + w.pid() + "/points/me"))).doesNotContain(SECRET);
		assertThat(notes.allText()).doesNotContain(SECRET);
	}

	@Test
	void leadDecisionClosesOrKeepsTheRequestOpenAndGuardsVersionAndRole() throws Exception {
		World w = world();
		UUID id = createRequest(w);
		String path = "/api/v1/support-requests/" + id + "/decision";
		String resolved = "{\"action\":\"RESOLVED_PRIVATELY\",\"response\":\"Đã trao đổi, cho gia hạn 1 ngày\",\"version\":%d}";
		assertThat(errorCode(postJson(w.a(), path, resolved.formatted(0), 403))).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postJson(w.lead(), path, "{\"action\":\"RESOLVED_PRIVATELY\",\"response\":\"\",\"version\":0}", 400)))
				.isEqualTo("VALIDATION_FAILED");

		MvcResult discuss = postJson(w.lead(), path,
				"{\"action\":\"NEED_MORE_DISCUSSION\",\"response\":\"Mình gọi bạn tối nay nhé\",\"version\":0}", 200);
		assertThat((String) at(discuss, "$.status")).isEqualTo("IN_REVIEW");
		assertThat((Integer) at(discuss, "$.version")).isEqualTo(1);
		assertThat((String) at(discuss, "$.reviewedBy.name")).isEqualTo(w.lead().name());
		assertThat(notes.ofType("SUPPORT_DECISION")).hasSize(1);
		assertThat(notes.ofType("SUPPORT_DECISION").get(0).recipientIds()).containsExactly(w.a().id());
		assertThat(errorCode(postJson(w.lead(), path, resolved.formatted(0), 409))).isEqualTo("VERSION_CONFLICT");

		MvcResult done = postJson(w.lead(), path, resolved.formatted(1), 200);
		assertThat((String) at(done, "$.status")).isEqualTo("RESOLVED");
		assertThat((String) at(done, "$.decision")).isEqualTo("RESOLVED_PRIVATELY");
		assertThat((Integer) at(done, "$.allowedActions.length()")).isZero();
		assertThat((String) at(getOk(w.a(), "/api/v1/support-requests/" + id), "$.leadResponse")).contains("gia hạn 1 ngày");
		assertThat(errorCode(postJson(w.lead(), path, resolved.formatted(2), 409))).isEqualTo("SUPPORT_REQUEST_CLOSED");
		assertThat(notes.allText()).doesNotContain(SECRET).doesNotContain("gia hạn");
	}

	@Test
	void riskReportsKeepPrivateContentOutAndOnlyLeadConfirmsDropoutRisk() throws Exception {
		World w = world();
		String path = "/api/v1/groups/" + w.pid() + "/risk-reports";
		String a1 = w.t("a1").taskId().toString();
		String b1 = w.t("b1").taskId().toString();
		String a2 = w.t("a2").taskId().toString();

		MvcResult r = postJson(w.a(), path, "{\"riskType\":\"DELAY_RISK\",\"taskId\":\"" + a1
				+ "\",\"publicSummary\":\"Phần API có thể cần thêm 1 ngày\",\"affectedTaskIds\":[\"" + b1 + "\",\"" + a2 + "\"]}", 201);
		assertThat((String) at(r, "$.status")).isEqualTo("OPEN");
		assertThat((String) at(r, "$.subject.name")).isEqualTo(w.a().name());
		assertThat((Integer) at(r, "$.affectedTaskIds.length()")).isEqualTo(2);
		assertThat((Object) at(r, "$.confirmedBy")).isNull();
		// RISK_RELEVANT chỉ tới người thực hiện task bị ảnh hưởng (B), không tới người báo (A), không lộ nội dung riêng
		assertThat(notes.ofType("RISK_RELEVANT")).hasSize(1);
		assertThat(notes.ofType("RISK_RELEVANT").get(0).recipientIds()).containsExactly(w.b().id());
		assertThat(notes.ofType("RISK_RELEVANT").get(0).payload()).containsEntry("taskId", b1);

		assertThat(errorCode(postJson(w.a(), path, "{\"riskType\":\"DROPOUT_RISK\",\"subjectUserId\":\"" + w.b().id()
				+ "\",\"publicSummary\":\"Bạn B có thể bỏ việc\"}", 403))).isEqualTo("LEAD_ONLY");
		assertThat(errorCode(postJson(w.b(), path, "{\"riskType\":\"DELAY_RISK\",\"taskId\":\"" + a1
				+ "\",\"publicSummary\":\"Không phải task của mình\"}", 403))).isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postJson(w.a(), path, "{\"riskType\":\"BLOCKED\",\"publicSummary\":\"Không có task cụ thể\"}", 403)))
				.isEqualTo("NOT_ASSIGNEE");
		assertThat(errorCode(postJson(w.lead(), path, "{\"riskType\":\"DROPOUT_RISK\",\"publicSummary\":\"Thiếu người liên quan\"}", 400)))
				.isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postJson(w.a(), path, "{\"riskType\":\"DELAY_RISK\",\"taskId\":\"" + a1
				+ "\",\"publicSummary\":\"ok ok\",\"affectedTaskIds\":[\"" + UUID.randomUUID() + "\"]}", 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(postJson(w.a(), path, "{\"riskType\":\"DELAY_RISK\",\"taskId\":\"" + a1 + "\",\"publicSummary\":\"x\"}", 400)))
				.isEqualTo("VALIDATION_FAILED");

		MvcResult dropout = postJson(w.lead(), path, "{\"riskType\":\"DROPOUT_RISK\",\"subjectUserId\":\"" + w.b().id()
				+ "\",\"publicSummary\":\"TL ghi nhận nguy cơ bỏ việc sau khi trao đổi\"}", 201);
		assertThat((String) at(dropout, "$.status")).isEqualTo("CONFIRMED");
		assertThat((String) at(dropout, "$.confirmedBy.name")).isEqualTo(w.lead().name());
		assertThat((String) at(dropout, "$.confirmedAt")).isNotBlank();
		assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE project_id = :p1 AND action = 'DROPOUT_RISK_CONFIRMED'", w.pid()))
				.isEqualTo(1);

		// mọi thành viên đọc được phần công khai; lọc theo trạng thái/task (kể cả task bị ảnh hưởng)
		MvcResult all = getOk(w.d(), path);
		assertThat((Integer) at(all, "$.totalItems")).isEqualTo(2);
		assertThat((Integer) at(getOk(w.d(), path + "?status=CONFIRMED"), "$.totalItems")).isEqualTo(1);
		assertThat((Integer) at(getOk(w.d(), path + "?taskId=" + b1), "$.totalItems")).isEqualTo(1);
		assertThat(errorCode(getStatus(w.d(), path + "?status=NOPE", 400))).isEqualTo("VALIDATION_FAILED");
		assertThat(errorCode(getStatus(fx.user("Người ngoài"), path, 404))).isEqualTo("NOT_A_GROUP_MEMBER");
	}

	@Test
	void riskCanBeResolvedOnceByLeadOrReporter() throws Exception {
		World w = world();
		String path = "/api/v1/groups/" + w.pid() + "/risk-reports";
		MvcResult r = postJson(w.a(), path, "{\"riskType\":\"BLOCKED\",\"taskId\":\"" + w.t("a1").taskId()
				+ "\",\"publicSummary\":\"Đang chờ tài khoản thử nghiệm\"}", 201);
		String resolve = "/api/v1/risk-reports/" + at(r, "$.id") + "/resolve";
		assertThat(errorCode(postJson(w.b(), resolve, "{\"version\":0}", 403))).isEqualTo("FORBIDDEN");
		assertThat(errorCode(postJson(w.a(), resolve, "{\"version\":7}", 409))).isEqualTo("VERSION_CONFLICT");
		MvcResult done = postJson(w.a(), resolve, "{\"note\":\"Đã có tài khoản\",\"version\":0}", 200);
		assertThat((String) at(done, "$.status")).isEqualTo("RESOLVED");
		assertThat((String) at(done, "$.resolvedAt")).isNotBlank();
		assertThat((Integer) at(done, "$.version")).isEqualTo(1);
		assertThat(errorCode(postJson(w.lead(), resolve, "{\"version\":1}", 409))).isEqualTo("RISK_ALREADY_RESOLVED");
		assertThat(errorCode(postJson(fx.user("Người ngoài"), resolve, "{\"version\":1}", 404))).isEqualTo("NOT_A_GROUP_MEMBER");
		assertThat(errorCode(postJson(w.lead(), "/api/v1/risk-reports/" + UUID.randomUUID() + "/resolve", "{\"version\":0}", 404)))
				.isEqualTo("NOT_FOUND");

		// TL giải quyết nguy cơ do người khác báo
		MvcResult dropout = postJson(w.lead(), path, "{\"riskType\":\"DROPOUT_RISK\",\"subjectUserId\":\"" + w.c().id()
				+ "\",\"publicSummary\":\"Nguy cơ bỏ việc đã được TL xác nhận\"}", 201);
		MvcResult closed = postJson(w.lead(), "/api/v1/risk-reports/" + at(dropout, "$.id") + "/resolve", "{\"version\":0}", 200);
		assertThat((String) at(closed, "$.status")).isEqualTo("RESOLVED");
		assertThat((String) at(closed, "$.confirmedBy.name")).isEqualTo(w.lead().name()); // giữ dấu vết xác nhận
	}
}
