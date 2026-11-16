package app.hanare.demo;

import app.hanare.demo.DemoAccounts.Acc;
import app.hanare.schedule.CheckpointJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nạp nhóm mẫu "Nhóm Lập trình Web" (TL + 4 thành viên) khi ứng dụng sẵn sàng, bằng CHÍNH API của ứng dụng (mọi ràng buộc
 * nghiệp vụ/DB được thực thi như thật, không tắt trigger) và đồng hồ {@link DemoClock} tua dần từ 09/11 tới 19/11/2026.
 * Idempotent: nhóm đã có thì bỏ qua. Cuối cùng đồng hồ đứng ở {@link #DEMO_NOW} (thứ Năm 10:00 giờ VN, kỳ 2 đang mở;
 * 15:00 thứ Sáu là giờ nhắc, 22:00 là giờ chốt — tua bằng {@code POST /api/v1/demo/clock}).
 */
@Component
@Profile("demo")
class DemoDataLoader {

	static final Instant DEMO_NOW = Instant.parse("2026-11-19T03:00:00Z");

	private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

	private final JdbcClient jdbc;
	private final DemoClock clock;
	private final CheckpointJob job;
	private final Environment env;
	private final JsonMapper json = JsonMapper.builder().build();
	private RestClient http;
	private volatile boolean ready;
	private final Map<String, String> tokens = new LinkedHashMap<>();

	DemoDataLoader(JdbcClient jdbc, DemoClock clock, CheckpointJob job, Environment env) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.job = job;
		this.env = env;
	}

	/** false cho tới khi nạp xong (để {@code GET /demo/accounts} và run-demo.cmd biết lúc nào dùng được). */
	boolean isReady() {
		return ready;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onReady() {
		boolean seeded = jdbc.sql("SELECT EXISTS (SELECT 1 FROM projects WHERE name = :n)").param("n", DemoAccounts.GROUP_NAME)
				.query(Boolean.class).single();
		if (seeded) {
			clock.set(DEMO_NOW);
			ready = true;
			log.info("Demo: nhóm mẫu đã có, bỏ qua nạp dữ liệu.");
			return;
		}
		http = RestClient.create("http://localhost:" + env.getProperty("local.server.port", env.getProperty("server.port", "8080")));
		try {
			seed();
			ready = true;
			log.info("Demo: đã nạp nhóm mẫu \"{}\". Đồng hồ demo = {}.", DemoAccounts.GROUP_NAME, DEMO_NOW);
		}
		catch (RuntimeException ex) {
			log.error("Demo: nạp dữ liệu mẫu thất bại", ex);
		}
	}

	// ------------------------------------------------------------------------------------------------ kịch bản

	private record Task(String id, String roundId) {
	}

	private void seed() {
		at("2026-11-09T02:00:00Z"); // thứ Hai 09:00 VN
		for (Acc a : DemoAccounts.ALL) {
			tokens.put(a.email(), ensureUser(a));
		}
		JsonNode created = post(DemoAccounts.TL, "/groups", m("name", DemoAccounts.GROUP_NAME, "courseName", "Lập trình Web",
				"startDate", "2026-11-09", "deadline", "2026-12-25", "scheduleAgreed", true, "schedule",
				m("weekday", 5, "cutoffTime", "22:00", "timezone", "Asia/Ho_Chi_Minh")));
		String g = created.at("/group/id").asString();
		String invite = created.at("/invite/code").asString();
		for (Acc a : List.of(DemoAccounts.AN, DemoAccounts.BAO, DemoAccounts.CUC, DemoAccounts.DUC)) {
			post(a, "/invites/" + invite + "/accept", null);
		}
		JsonNode cycles = get(DemoAccounts.TL, "/groups/" + g + "/checkpoint-cycles?sort=asc&size=10").path("items");
		String k1 = cycles.get(0).path("id").asString();
		String k2 = cycles.get(1).path("id").asString();

		// Task đã duyệt kế hoạch TRƯỚC khi khóa phương án điểm: A = Dễ/Trung bình/Khó, C = 5 task Khó.
		Task a1 = task(g, DemoAccounts.AN, k1, "Thiết kế giao diện trang chủ", "EASY", "2026-11-09T03:00:00Z", "2026-11-12T13:00:00Z");
		Task a2 = task(g, DemoAccounts.AN, k1, "API quản lý người dùng", "MEDIUM", "2026-11-09T03:00:00Z", "2026-11-13T04:00:00Z");
		Task a3 = task(g, DemoAccounts.AN, k2, "Tích hợp cổng thanh toán", "HARD", "2026-11-14T01:00:00Z", "2026-11-18T13:00:00Z");
		Task b1 = task(g, DemoAccounts.BAO, k1, "Thiết kế cơ sở dữ liệu", "MEDIUM", "2026-11-09T03:00:00Z", "2026-11-12T10:00:00Z");
		Task b2 = task(g, DemoAccounts.BAO, k2, "Trang danh sách sản phẩm", "EASY", "2026-11-14T01:00:00Z", "2026-11-19T10:00:00Z");
		Task c1 = task(g, DemoAccounts.CUC, k1, "Module đăng nhập và phân quyền", "HARD", "2026-11-09T03:00:00Z", "2026-11-13T09:00:00Z");
		Task c2 = task(g, DemoAccounts.CUC, k2, "Module giỏ hàng", "HARD", "2026-11-14T01:00:00Z", "2026-11-17T13:00:00Z");
		Task c3 = task(g, DemoAccounts.CUC, k2, "Module đơn hàng", "HARD", "2026-11-14T01:00:00Z", "2026-11-20T09:00:00Z");
		task(g, DemoAccounts.CUC, k2, "Tối ưu hiệu năng truy vấn", "HARD", "2026-11-14T01:00:00Z", "2026-11-20T11:00:00Z");
		Task c5 = task(g, DemoAccounts.CUC, k2, "Viết kiểm thử end-to-end", "HARD", "2026-11-14T01:00:00Z", "2026-11-20T13:00:00Z");
		Task d1 = task(g, DemoAccounts.DUC, k1, "Viết tài liệu API", "EASY", "2026-11-09T03:00:00Z", "2026-11-12T12:00:00Z");
		Task d2 = task(g, DemoAccounts.DUC, k2, "Thiết lập CI/CD", "MEDIUM", "2026-11-14T01:00:00Z", "2026-11-20T08:00:00Z");
		post(DemoAccounts.TL, "/groups/" + g + "/point-policy/activate", m("confirmedWithMembers", true));

		at("2026-11-11T05:00:00Z");
		submit(DemoAccounts.AN, a1, "https://github.com/hanare-demo/web/pull/1", "Đã xong giao diện trang chủ, có bản mobile.");
		at("2026-11-12T08:00:00Z");
		submit(DemoAccounts.BAO, b1, "https://dbdiagram.io/d/hanare-demo", "Lược đồ CSDL đã chuẩn hóa tới 3NF.");
		at("2026-11-12T09:00:00Z");
		submit(DemoAccounts.DUC, d1, "https://github.com/hanare-demo/web/wiki/api", "Tài liệu API bản đầu.");
		at("2026-11-13T07:00:00Z"); // trễ hạn 3 giờ so với 04:00Z
		submit(DemoAccounts.AN, a2, "https://github.com/hanare-demo/web/pull/2", "API người dùng đã chạy, nộp trễ vì chờ dữ liệu mẫu.");

		// Hết kỳ 1 → TL kiểm tra: duyệt, yêu cầu sửa, chuyển kỳ.
		at("2026-11-13T15:30:00Z"); // thứ Sáu 22:30 VN
		review(DemoAccounts.TL, a1, "ACCEPTED", null);
		review(DemoAccounts.TL, a2, "ACCEPTED", null);
		review(DemoAccounts.TL, b1, "ACCEPTED", null);
		review(DemoAccounts.TL, d1, "FIX_REQUIRED", "Thiếu mô tả các endpoint /auth và ví dụ phản hồi lỗi.");
		carryover(DemoAccounts.TL, c1, k2, "2026-11-18T09:00:00Z");
		carryover(DemoAccounts.TL, d1, k2, "2026-11-18T13:00:00Z");

		at("2026-11-16T03:00:00Z");
		post(DemoAccounts.AN, "/tasks/" + a3.id() + "/progress", m("note", "Đã tích hợp sandbox, đang xử lý callback.", "riskStatus", "NORMAL"));
		post(DemoAccounts.BAO, "/tasks/" + b2.id() + "/start", m("version", get(DemoAccounts.BAO, "/tasks/" + b2.id()).path("version").asLong()));
		at("2026-11-17T08:00:00Z");
		submit(DemoAccounts.DUC, d1, "https://github.com/hanare-demo/web/wiki/api-v2",
				"Đã bổ sung endpoint /auth và ví dụ lỗi.");
		at("2026-11-17T09:00:00Z");
		post(DemoAccounts.CUC, "/groups/" + g + "/risk-reports", m("riskType", "DELAY_RISK", "taskId", c2.id(),
				"publicSummary", "Module giỏ hàng có thể cần thêm 1 ngày", "affectedTaskIds", List.of(c3.id())));
		JsonNode sr = post(DemoAccounts.CUC, "/tasks/" + c5.id() + "/support-requests", m("obstacleType", "TIME",
				"description", "Mình đang giữ 5 task Khó cùng lúc, không kịp phần kiểm thử.", "neededSupport",
				"Nhờ nhóm làm thay toàn bộ phần kiểm thử end-to-end", "impact", "Có thể trễ cả đợt phát hành"));
		at("2026-11-18T03:00:00Z");
		JsonNode help = post(DemoAccounts.TL, "/groups/" + g + "/help-requests", m("taskId", c5.id(), "beneficiaryId", userId(DemoAccounts.CUC),
				"supportRequestId", sr.path("id").asString(), "title", "Viết kiểm thử end-to-end (toàn bộ task)",
				"description", "Cần người viết bộ kiểm thử end-to-end cho luồng đăng nhập, giỏ hàng và đặt hàng.",
				"estimatedEffortMinutes", 360, "skillNotes", "Quen Playwright hoặc Cypress là lợi thế", "privacyChecked", true));
		post(DemoAccounts.BAO, "/help-requests/" + help.path("id").asString() + "/offers", m("message", "Mình rảnh tối nay và quen Playwright.",
				"acknowledgedScope", true, "helpRequestVersion", help.path("version").asLong()));
		at("2026-11-18T12:30:00Z"); // 19:30 VN, hạn 20:00 VN
		submit(DemoAccounts.AN, a3, "https://github.com/hanare-demo/web/pull/3", "Thanh toán sandbox chạy đủ luồng.");
		at("2026-11-18T13:00:00Z");
		JsonNode report = get(DemoAccounts.AN, "/groups/" + g + "/checkpoint-cycles/" + k2 + "/my-report");
		post(DemoAccounts.AN, "/groups/" + g + "/checkpoint-cycles/" + k2 + "/my-report/submit", m("items", reportItems(report), "version", null));
		at("2026-11-18T14:00:00Z");
		post(DemoAccounts.DUC, "/tasks/" + d2.id() + "/support-requests", m("obstacleType", "TECHNICAL",
				"description", "Chưa có quyền tạo secret trên kho mã nguồn của nhóm.", "neededSupport", "Cần TL cấp quyền quản trị kho"));
		at(DEMO_NOW.toString());
	}

	// ------------------------------------------------------------------------------------------------ bước nhỏ

	/** Đặt đồng hồ + chạy job tới hạn (mở/đóng kỳ, nhắc) như scheduler thật. */
	private void at(String iso) {
		Instant t = Instant.parse(iso);
		clock.set(t);
		job.runOnce(t);
	}

	private String ensureUser(Acc a) {
		try {
			return http("POST", "/auth/register", null, m("name", a.name(), "email", a.email(), "password", DemoAccounts.PASSWORD))
					.path("accessToken").asString();
		}
		catch (IllegalStateException exists) {
			return http("POST", "/auth/login", null, m("email", a.email(), "password", DemoAccounts.PASSWORD)).path("accessToken").asString();
		}
	}

	/** Tạo task + member gửi kế hoạch + TL duyệt. */
	private Task task(String g, Acc assignee, String cycleId, String title, String difficulty, String startAt, String deadline) {
		JsonNode t = post(DemoAccounts.TL, "/groups/" + g + "/tasks", m("title", title, "assigneeId", userId(assignee), "cycleId", cycleId,
				"description", "Phạm vi: " + title.toLowerCase() + " cho đồ án website bán hàng của nhóm.",
				"expectedOutput", "Mã nguồn đã hợp nhất vào nhánh chính kèm hướng dẫn chạy", "startAt", startAt, "deadline", deadline,
				"difficulty", difficulty));
		String plan = t.at("/pendingRevision/id").asString();
		JsonNode submitted = post(assignee, "/plans/" + plan + "/submit", m("version", t.at("/pendingRevision/version").asLong()));
		post(DemoAccounts.TL, "/plans/" + plan + "/review", m("decision", "APPROVED", "version", submitted.at("/plan/version").asLong()));
		return new Task(t.path("id").asString(), t.path("roundId").asString());
	}

	private void submit(Acc who, Task t, String url, String note) {
		JsonNode d = get(who, "/tasks/" + t.id());
		post(who, "/task-rounds/" + d.at("/currentRound/id").asString() + "/submissions", m("planRevisionId", d.at("/effectivePlan/id").asString(),
				"resultNote", note, "evidence", List.of(m("type", "URL", "url", url, "label", "Kết quả"))));
	}


	private void review(Acc lead, Task t, String decision, String issue) {
		JsonNode subs = get(lead, "/task-rounds/" + t.roundId() + "/submissions").path("items");
		post(lead, "/task-submissions/" + subs.get(subs.size() - 1).path("id").asString() + "/review", m("decision", decision, "issueDescription", issue));
	}

	private void carryover(Acc lead, Task t, String targetCycle, String deadline) {
		post(lead, "/task-rounds/" + t.roundId() + "/carryover", m("targetCycleId", targetCycle, "deadline", deadline));
	}

	/** Dùng lại các dòng báo cáo do server điền sẵn để "xác nhận hàng loạt". */
	private static List<Map<String, Object>> reportItems(JsonNode report) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (JsonNode i : report.path("items")) {
			items.add(m("taskId", i.path("taskId").asString(), "declaredResult", i.path("declaredResult").asString(), "riskLevel",
					i.path("riskLevel").asString(), "note", "Đã nộp, chờ TL duyệt."));
		}
		return items;
	}

	private String userId(Acc a) {
		return jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", a.email()).query(UUID.class).single().toString();
	}

	// ------------------------------------------------------------------------------------------------ HTTP

	private JsonNode get(Acc as, String path) {
		return http("GET", path, tokens.get(as.email()), null);
	}

	private JsonNode post(Acc as, String path, Object body) {
		return http("POST", path, tokens.get(as.email()), body);
	}

	private JsonNode http(String method, String path, String token, Object body) {
		try {
			RestClient.RequestBodySpec spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/api/v1" + path)
					.contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString());
			if (token != null) {
				spec.header("Authorization", "Bearer " + token);
			}
			if (body != null) {
				spec.body(json.writeValueAsString(body));
			}
			String text = spec.retrieve().body(String.class);
			return text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
		}
		catch (RestClientResponseException ex) {
			throw new IllegalStateException(method + " " + path + " → " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
		}
	}

	private static Map<String, Object> m(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}
}
