package app.hanare.dashboard;

import app.hanare.common.ApiException;
import app.hanare.help.HandoffService;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.task.J;
import app.hanare.task.Scope;
import app.hanare.task.TaskReader;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskViews;
import app.hanare.task.TaskViews.Extras;
import app.hanare.task.TaskViews.Viewer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Solution 2 · L1 — khung quản lý Member của TL: TOÀN BỘ task đang phụ trách của một thành viên (trong tuần, tồn đọng và đã
 * hoàn thành), do SERVER phân nhóm và sắp xếp để FE chỉ hiển thị:
 * <ol>
 * <li>{@code OVERDUE} — quá deadline mà chưa có kết quả hợp lệ (đỏ pastel, nhãn "Quá hạn"). Task nộp ĐÚNG HẠN và đang chờ TL
 * duyệt KHÔNG thuộc nhóm này.</li>
 * <li>{@code NEEDS_HELP} — Member đã bấm "Cần giúp đỡ" và TL chưa đóng đề nghị (vàng pastel, nhãn "Cần hỗ trợ").</li>
 * <li>{@code ACTIVE} — còn lại, chưa nộp/đang làm.</li>
 * <li>{@code AWAITING_REVIEW} — đã nộp đúng hạn, chờ TL duyệt (nhãn riêng).</li>
 * <li>{@code DONE} — đã nghiệm thu, xuống cuối.</li>
 * </ol>
 * Một task chỉ xuất hiện MỘT dòng: vừa quá hạn vừa cần giúp → nhóm {@code OVERDUE} kèm cờ {@code needsHelp=true}.
 * Chỉ TL gọi được vì có cờ về đề nghị giúp đỡ riêng; không trả nội dung riêng, chỉ {@code supportRequestId} để TL mở S07.
 */
@Service
public class MemberTaskBoardService {

	private static final Map<String, Integer> RANK = Map.of("OVERDUE", 0, "NEEDS_HELP", 1, "ACTIVE", 2, "AWAITING_REVIEW", 3,
			"DONE", 4);

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Scope.Factory scopes;
	private final TaskReader reader;
	private final TaskViews views;
	private final app.hanare.points.TaskReader pointTasks;
	private final HandoffService handoff;

	public MemberTaskBoardService(JdbcClient jdbc, GroupAccess access, Scope.Factory scopes, TaskReader reader, TaskViews views,
			app.hanare.points.TaskReader pointTasks, HandoffService handoff) {
		this.jdbc = jdbc;
		this.access = access;
		this.scopes = scopes;
		this.reader = reader;
		this.views = views;
		this.pointTasks = pointTasks;
		this.handoff = handoff;
	}

	private record Line(Ctx ctx, Map<String, Object> summary, String section, Instant deadline, String title) {
	}

	@Transactional(readOnly = true)
	public Map<String, Object> board(UUID projectId, UUID memberId, CurrentUser user) {
		GroupMembership lead = access.requireLeader(projectId, user);
		if (!access.isActiveMember(projectId, memberId)) {
			throw ApiException.notFound("Thành viên không thuộc nhóm.");
		}
		Scope s = scopes.of(projectId);
		Instant now = s.now();
		Viewer viewer = new Viewer(user.id(), lead.role().name());
		List<Ctx> mine = reader.loadAll(reader.currentRoundIds(projectId)).values().stream()
				.filter(c -> memberId.equals(c.executor())).toList();
		Extras extras = views.extras(mine);

		Map<UUID, String[]> help = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (task_id) task_id, id, status FROM support_requests
				 WHERE project_id = :p AND requester_id = :u AND status IN ('OPEN', 'IN_REVIEW')
				 ORDER BY task_id, created_at DESC
				""").param("p", projectId).param("u", memberId).query((rs, i) -> {
			help.put(J.uuid(rs, "task_id"), new String[] { rs.getString("id"), rs.getString("status") });
			return 0;
		}).list();
		Map<UUID, String[]> published = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (task_id) task_id, id, status FROM help_requests
				 WHERE project_id = :p AND status IN ('OPEN', 'MATCHED', 'IN_PROGRESS', 'SUBMITTED')
				 ORDER BY task_id, created_at DESC
				""").param("p", projectId).query((rs, i) -> {
			published.put(J.uuid(rs, "task_id"), new String[] { rs.getString("id"), rs.getString("status") });
			return 0;
		}).list();

		List<Line> lines = new ArrayList<>();
		for (Ctx c : mine) {
			Map<String, Object> summary = views.summary(s, c, viewer, extras);
			UUID taskId = c.round().taskId();
			String status = (String) summary.get("taskStatus");
			String timeliness = (String) summary.get("timeliness");
			Instant deadline = c.deadline();
			boolean done = "DONE".equals(status);
			// T-05: "quá hạn" = SAU deadline (đúng quy tắc của TaskReader.eval: now > deadline), nhất quán với `timeliness`.
			boolean overdue = c.committed() && !done && deadline != null && deadline.isBefore(now)
					&& !"ON_TIME_PENDING_REVIEW".equals(timeliness);
			boolean needsHelp = !done && help.containsKey(taskId);
			boolean awaiting = "SUBMITTED".equals(status);
			String section = done ? "DONE" : overdue ? "OVERDUE" : needsHelp ? "NEEDS_HELP" : awaiting ? "AWAITING_REVIEW" : "ACTIVE";
			lines.add(new Line(c, summary, section, deadline, String.valueOf(summary.get("title"))));
		}
		lines.sort(Comparator.<Line>comparingInt(l -> RANK.get(l.section()))
				.thenComparing(l -> l.deadline() == null ? Instant.MAX : l.deadline()).thenComparing(Line::title));

		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String k : List.of("OVERDUE", "NEEDS_HELP", "ACTIVE", "AWAITING_REVIEW", "DONE")) {
			counts.put(k, 0);
		}
		List<Map<String, Object>> tasks = new ArrayList<>();
		for (Line l : lines) {
			UUID taskId = l.ctx().round().taskId();
			String[] sr = help.get(taskId);
			String[] hr = published.get(taskId);
			boolean done = "DONE".equals(l.section());
			Optional<ApiException> blocker = done ? Optional.of(app.hanare.points.Err.conflict("TASK_ALREADY_DONE",
					"Task đã được nghiệm thu.")) : publishBlocker(projectId, taskId, memberId, hr);
			counts.merge(l.section(), 1, Integer::sum);
			tasks.add(J.o("task", l.summary(), "section", l.section(), "overdue", "OVERDUE".equals(l.section()),
					"needsHelp", !done && sr != null, "awaitingReview", "AWAITING_REVIEW".equals(l.section()), "done", done,
					"supportRequestId", done || sr == null ? null : sr[0], "supportRequestStatus", done || sr == null ? null : sr[1],
					"helpRequestId", hr == null ? null : hr[0], "helpRequestStatus", hr == null ? null : hr[1],
					"canPublishToWorkspace", blocker.isEmpty(), "publishBlockedCode", blocker.map(ApiException::code).orElse(null),
					"publishBlockedMessage", blocker.map(ApiException::getMessage).orElse(null)));
		}
		return J.o("member", s.user(memberId), "serverTime", J.t(now), "counts", counts, "tasks", tasks);
	}

	/** Có thể `Đưa lên Workspace` không — cùng điều kiện với {@code POST /help-requests} (không tạo trạng thái khác). */
	private Optional<ApiException> publishBlocker(UUID projectId, UUID taskId, UUID memberId, String[] openHelp) {
		if (openHelp != null) {
			return Optional.of(app.hanare.points.Err.conflict("HELP_REQUEST_ALREADY_OPEN",
					"Task này đã có yêu cầu hỗ trợ còn hiệu lực."));
		}
		Optional<TaskFacts> facts = pointTasks.facts(projectId, taskId);
		if (facts.isEmpty()) {
			return Optional.of(ApiException.notFound("Không tìm thấy task."));
		}
		TaskFacts f = facts.get();
		try {
			handoff.requireTransferable(f);
		}
		catch (ApiException e) {
			return Optional.of(e);
		}
		UUID leader = scopes.of(projectId).leaderId();
		if (memberId.equals(leader)) {
			UUID independent = f.independentApproverId();
			if (independent == null || independent.equals(memberId) || !access.isActiveMember(projectId, independent)) {
				return Optional.of(app.hanare.points.Err.conflict("INDEPENDENT_REVIEWER_REQUIRED",
						"Task của TL cần người duyệt độc lập đã được chỉ định trước khi đăng yêu cầu hỗ trợ."));
			}
		}
		return Optional.empty();
	}
}
