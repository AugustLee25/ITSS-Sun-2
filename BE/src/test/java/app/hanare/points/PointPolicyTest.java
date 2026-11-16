package app.hanare.points;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import app.hanare.testsupport.DbFixtures.GroupRef;
import app.hanare.testsupport.DbFixtures.TaskRef;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.HanareTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** K: point-policy GET/preview/activate — công thức 12.8.2, khóa giá, dự phòng, ADDITIONAL, lỗi quyền/trạng thái. */
@HanareTest
@Import(RecordingNotifications.Config.class)
class PointPolicyTest extends ApiTestBase {

	private String base(World w) {
		return "/api/v1/groups/" + w.pid() + "/point-policy";
	}

	private List<Integer> taskUnits(MvcResult r, UserRef user) throws Exception {
		return JsonPath.read(body(r), "$.allocation.members[?(@.user.userId=='" + user.id() + "')].tasks[*].points.pointUnits");
	}

	@Test
	void getBeforeActivationSaysNoApprovedPolicy() throws Exception {
		World w = world();
		MvcResult asLead = getOk(w.lead(), base(w));
		assertThat((Boolean) at(asLead, "$.active")).isFalse();
		assertThat((Object) at(asLead, "$.policy")).isNull();
		assertThat((String) at(asLead, "$.statusLabel")).isEqualTo("Chưa có phương án điểm đã duyệt");
		MvcResult asMember = getOk(w.a(), base(w));
		assertThat((Boolean) at(asMember, "$.active")).isFalse();
		getStatus(fx.user("Người ngoài"), base(w), 404);
	}

	@Test
	void previewPricesBasketsByLargestRemainderAndSumsToTheQuota() throws Exception {
		World w = world();
		MvcResult r = postJson(w.lead(), base(w) + "/preview", "{}", 200);
		assertThat((String) at(r, "$.basis")).isEqualTo("INITIAL");
		assertThat((Integer) at(r, "$.nextVersionNo")).isEqualTo(1);
		assertThat((Integer) at(r, "$.memberCount")).isEqualTo(5);
		assertThat((Boolean) at(r, "$.canActivate")).isTrue();
		assertThat((Boolean) at(r, "$.sumCheck.ok")).isTrue();
		assertThat((Integer) at(r, "$.sumCheck.actualUnits")).isEqualTo(1_000_000);
		// A: một Dễ + một Trung bình + một Khó => 3,3333 / 6,6667 / 10,0000
		assertThat(taskUnits(r, w.a())).containsExactlyInAnyOrder(33_333, 66_667, 100_000);
		List<String> labels = JsonPath.read(body(r),
				"$.allocation.members[?(@.user.userId=='" + w.a().id() + "')].tasks[*].points.percentLabel");
		assertThat(labels).containsExactlyInAnyOrder("3,3333", "6,6667", "10,0000");
		// C: năm task Khó = 4% mỗi task; B/D/L một task = toàn bộ 20%
		assertThat(taskUnits(r, w.c())).containsExactly(40_000, 40_000, 40_000, 40_000, 40_000);
		assertThat(taskUnits(r, w.b())).containsExactly(200_000);
		assertThat((Integer) at(r, "$.allocation.unallocatedPoints.pointUnits")).isZero();
		assertThat(count("SELECT COUNT(*) FROM point_policies WHERE project_id = :p1", w.pid())).isZero(); // preview không ghi
	}

	@Test
	void previewAndActivateAreLeadOnlyAndRejectUnapprovedTasks() throws Exception {
		World w = world();
		MvcResult member = postJson(w.a(), base(w) + "/preview", "{}", 403);
		assertThat(errorCode(member)).isEqualTo("LEAD_ONLY");
		MvcResult outsider = postJson(fx.user("Người ngoài"), base(w) + "/preview", "{}", 404);
		assertThat(errorCode(outsider)).isEqualTo("NOT_A_GROUP_MEMBER");
		MvcResult memberActivate = postIdem(w.a(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 403);
		assertThat(errorCode(memberActivate)).isEqualTo("LEAD_ONLY");

		// task chưa có kế hoạch được duyệt
		UUID draft = jdbc.sql("INSERT INTO tasks (project_id, created_by) VALUES (:p, :c) RETURNING id")
				.param("p", w.pid()).param("c", w.lead().id()).query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO task_review_rounds (project_id, task_id, round_no, cycle_id, reason, state, created_by)
				VALUES (:p, :t, 1, :c, 'INITIAL', 'DRAFT', :u)
				""").param("p", w.pid()).param("t", draft).param("c", w.cycle().id()).param("u", w.lead().id()).update();
		MvcResult preview = postJson(w.lead(), base(w) + "/preview", "{}", 200);
		assertThat((Boolean) at(preview, "$.canActivate")).isFalse();
		assertThat((String) at(preview, "$.blockingIssues[0].code")).isEqualTo("POLICY_TASKS_NOT_APPROVED");
		assertThat((String) at(preview, "$.blockingIssues[0].taskId")).isEqualTo(draft.toString());
		MvcResult activate = postIdem(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 422);
		assertThat(errorCode(activate)).isEqualTo("POLICY_TASKS_NOT_APPROVED");
		assertThat(count("SELECT COUNT(*) FROM point_policies WHERE project_id = :p1", w.pid())).isZero();
	}

	@Test
	void activateLocksPricesRequiresKeyAndConfirmationAndIsRepeatSafe() throws Exception {
		World w = world();
		MvcResult noKey = postJson(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 400);
		assertThat(errorCode(noKey)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		MvcResult notConfirmed = postIdem(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":false}", 400);
		assertThat(errorCode(notConfirmed)).isEqualTo("VALIDATION_FAILED");

		MvcResult r = postIdem(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 201);
		assertThat((Integer) at(r, "$.fundedTaskCount")).isEqualTo(11);
		assertThat((String) at(r, "$.policy.status")).isEqualTo("ACTIVE");
		assertThat((Integer) at(r, "$.policy.totalPoints.pointUnits")).isEqualTo(1_000_000);
		assertThat((Integer) at(r, "$.policy.memberCountAtBaseline")).isEqualTo(5);
		assertThat(count("SELECT COUNT(*) FROM task_point_budgets WHERE project_id = :p1", w.pid())).isEqualTo(11);
		assertThat(count("SELECT SUM(point_units) FROM task_point_budgets WHERE project_id = :p1", w.pid())).isEqualTo(1_000_000);
		assertThat(count("SELECT COUNT(*) FROM task_point_assignments WHERE project_id = :p1 AND status = 'CURRENT'", w.pid()))
				.isEqualTo(11);
		assertThat(count("SELECT COUNT(*) FROM point_ledger_entries WHERE project_id = :p1", w.pid())).isZero(); // khóa giá không ghi điểm
		assertThat(notes.ofType("POINT_POLICY_ACTIVATED")).hasSize(1);
		assertThat(notes.ofType("POINT_POLICY_ACTIVATED").get(0).recipientIds()).hasSize(5);

		// khóa lại: không tạo phiên bản/quỹ mới
		MvcResult again = postIdem(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 201);
		assertThat((Integer) at(again, "$.fundedTaskCount")).isZero();
		assertThat(count("SELECT COUNT(*) FROM point_policies WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(count("SELECT SUM(point_units) FROM task_point_budgets WHERE project_id = :p1", w.pid())).isEqualTo(1_000_000);

		MvcResult asLead = getOk(w.lead(), base(w));
		assertThat((Boolean) at(asLead, "$.active")).isTrue();
		assertThat((Integer) at(asLead, "$.allocation.members.length()")).isEqualTo(5);
		MvcResult asMember = getOk(w.a(), base(w));
		assertThat((Boolean) at(asMember, "$.active")).isTrue();
		assertThat((Object) at(asMember, "$.allocation")).isNull(); // Member không thấy bảng W_i
	}

	@Test
	void groupNotDivisibleByNKeepsExactQuotaAndHonoursReserveAndZeroWeight() throws Exception {
		GroupRef g = fx.group("Nhóm 3", 3);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		var cycle = fx.weeklyCycles(g.projectId(), schedule, 2).get(0);
		UserRef m1 = g.members().get(0);
		UserRef m2 = g.members().get(1);
		var deadline = cycle.closesAt().minus(Duration.ofDays(1));
		List<TaskRef> m1Tasks = new ArrayList<>();
		for (String diff : List.of("EASY", "MEDIUM", "HARD")) {
			m1Tasks.add(fx.approvedTask(g.projectId(), g.leader().id(), m1.id(), cycle, diff, deadline));
		}
		fx.approvedTask(g.projectId(), g.leader().id(), m2.id(), cycle, "HARD", deadline);
		String path = "/api/v1/groups/" + g.projectId() + "/point-policy";

		// B_i bằng phần dư lớn nhất giữa 3 người, tie-break id nhỏ nhất
		List<UUID> sorted = g.all().stream().map(UserRef::id).sorted(Comparator.comparing(UUID::toString)).toList();
		long[] shares = PointMath.allocate(1_000_000, new long[] { 1, 1, 1 }, sorted);
		long b1 = shares[sorted.indexOf(m1.id())];

		String reserve = "{\"reserves\":[{\"userId\":\"" + m1.id() + "\",\"reservePointUnits\":50000}]}";
		MvcResult r = postJson(g.leader(), path + "/preview", reserve, 200);
		assertThat((Boolean) at(r, "$.canActivate")).isTrue();
		assertThat((Integer) at(r, "$.sumCheck.actualUnits")).isEqualTo(1_000_000);
		List<Integer> initials = JsonPath.read(body(r), "$.allocation.members[*].initialPoints.pointUnits");
		assertThat(initials).containsExactlyInAnyOrder(333_334, 333_333, 333_333);
		List<Integer> m1Units = taskUnits(r, m1);
		assertThat(m1Units.stream().mapToLong(Integer::longValue).sum()).isEqualTo(b1 - 50_000);
		List<Integer> m1Reserve = JsonPath.read(body(r),
				"$.allocation.members[?(@.user.userId=='" + m1.id() + "')].reservePoints.pointUnits");
		assertThat(m1Reserve).containsExactly(50_000);
		// TL không có task: W_i = 0 → giữ B_i làm quỹ chưa phân bổ + cảnh báo, không chặn
		List<String> warnings = JsonPath.read(body(r),
				"$.allocation.members[?(@.user.userId=='" + g.leader().id() + "')].warnings[0]");
		assertThat(warnings).hasSize(1);
		long leadB = shares[sorted.indexOf(g.leader().id())];
		assertThat((Integer) at(r, "$.allocation.unallocatedPoints.pointUnits")).isEqualTo((int) (leadB + 50_000));

		// R_i vượt phần quỹ còn tự do / người ngoài phương án -> POINT_POLICY_INVALID
		String tooBig = "{\"reserves\":[{\"userId\":\"" + m1.id() + "\",\"reservePointUnits\":999999}]}";
		MvcResult bad = postJson(g.leader(), path + "/preview", tooBig, 200);
		assertThat((Boolean) at(bad, "$.canActivate")).isFalse();
		assertThat((String) at(bad, "$.blockingIssues[0].code")).isEqualTo("POINT_POLICY_INVALID");
		MvcResult rejected = postIdem(g.leader(), path + "/activate",
				"{\"confirmedWithMembers\":true,\"reserves\":[{\"userId\":\"" + m1.id() + "\",\"reservePointUnits\":999999}]}", 422);
		assertThat(errorCode(rejected)).isEqualTo("POINT_POLICY_INVALID");

		postIdem(g.leader(), path + "/activate", "{\"confirmedWithMembers\":true," + reserve.substring(1), 201);
		assertThat(count("SELECT SUM(initial_units) FROM member_point_budgets WHERE project_id = :p1", g.projectId()))
				.isEqualTo(1_000_000);
		assertThat(count("SELECT COALESCE(SUM(reserve_units), 0) FROM member_point_budgets WHERE project_id = :p1", g.projectId()))
				.isEqualTo(leadB + 50_000);
		assertThat(m1Tasks).hasSize(3);
	}

	@Test
	void additionalPricingTakesLaterTasksOutOfTheReserveOnlyAndNeverAddsFund() throws Exception {
		World w = world();
		String reserve = "{\"confirmedWithMembers\":true,\"reserves\":[{\"userId\":\"" + w.a().id() + "\",\"reservePointUnits\":60000}]}";
		postIdem(w.lead(), base(w) + "/activate", reserve, 201);
		assertThat(count("SELECT reserve_units FROM member_point_budgets WHERE member_id = :p1", w.a().id())).isEqualTo(60_000);

		// task phát sinh SAU khi khóa: chưa có điểm cho tới lần định giá kế tiếp
		fx.approvedTask(w.pid(), w.lead().id(), w.a().id(), w.cycle(), "HARD", w.cycle().closesAt());
		MvcResult preview = postJson(w.lead(), base(w) + "/preview", "{}", 200);
		assertThat((String) at(preview, "$.basis")).isEqualTo("ADDITIONAL");
		assertThat((Boolean) at(preview, "$.canActivate")).isTrue();
		assertThat((Boolean) at(preview, "$.sumCheck.ok")).isTrue();
		MvcResult done = postIdem(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 201);
		assertThat((Integer) at(done, "$.fundedTaskCount")).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM point_policies WHERE project_id = :p1", w.pid())).isEqualTo(1);
		assertThat(count("SELECT reserve_units FROM member_point_budgets WHERE member_id = :p1", w.a().id())).isZero();
		assertThat(count("SELECT SUM(point_units) FROM task_point_budgets WHERE project_id = :p1", w.pid()))
				.isEqualTo(1_000_000); // tổng quỹ không đổi
		assertThat(count("SELECT point_units FROM task_point_budgets WHERE project_id = :p1 ORDER BY created_at DESC, id LIMIT 1",
				w.pid()))
				.isEqualTo(60_000);

		// dự phòng đã hết: task mới nữa không thể được định giá ngoài quỹ
		fx.approvedTask(w.pid(), w.lead().id(), w.a().id(), w.cycle(), "EASY", w.cycle().closesAt());
		MvcResult exhausted = postJson(w.lead(), base(w) + "/preview", "{}", 200);
		assertThat((Boolean) at(exhausted, "$.canActivate")).isFalse();
		assertThat((String) at(exhausted, "$.blockingIssues[0].code")).isEqualTo("POINT_POLICY_INVALID");
		MvcResult blocked = postIdem(w.lead(), base(w) + "/activate", "{\"confirmedWithMembers\":true}", 422);
		assertThat(errorCode(blocked)).isEqualTo("POINT_POLICY_INVALID");
		assertThat(count("SELECT SUM(point_units) FROM task_point_budgets WHERE project_id = :p1", w.pid())).isEqualTo(1_000_000);
	}

	@Test
	void activationRequestBodyIsRequiredToBeJson() throws Exception {
		World w = world();
		call(w.lead(), post(base(w) + "/activate").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
				.content("{not json"), 400);
	}
}
