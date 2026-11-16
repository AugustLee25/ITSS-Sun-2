package app.hanare.task;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.schedule.CutoffHook;
import app.hanare.schedule.CycleService;
import app.hanare.security.GroupAccess;
import app.hanare.task.CycleData.Reports;
import app.hanare.task.CycleData.Row;
import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Eval;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vòng đời kỳ checkpoint phía task: {@link CutoffHook} (chụp bất biến tại giờ chốt + một thông báo cho TL),
 * {@link #leadSummary} (LeadCycleSummary) và {@link #finalizeIfDone}.
 */
@Component
public class CycleLifecycle implements CutoffHook {

	private final JdbcClient jdbc;
	private final Scope.Factory scopes;
	private final TaskReader reader;
	private final CycleData data;
	private final Ops ops;
	private final GroupAccess access;
	private final JsonMapper mapper;

	public CycleLifecycle(JdbcClient jdbc, Scope.Factory scopes, TaskReader reader, CycleData data, Ops ops, GroupAccess access,
			JsonMapper mapper) {
		this.jdbc = jdbc;
		this.scopes = scopes;
		this.reader = reader;
		this.data = data;
		this.ops = ops;
		this.access = access;
		this.mapper = mapper;
	}

	// ------------------------------------------------------------------ giờ chốt
	@Override
	@Transactional
	public void onCycleClosed(CycleService.CycleRef ref) {
		UUID projectId = ref.projectId();
		UUID cycleId = ref.id();
		jdbc.sql("SELECT id FROM checkpoint_cycles WHERE id = :c FOR UPDATE").param("c", cycleId).query(UUID.class).list();
		if (data.frozen(cycleId)) {
			return; // job chạy lại: không tạo bản ghi/thông báo thứ hai
		}
		Scope s = scopes.of(projectId);
		CycleRow cy = s.cycle(cycleId);
		Instant now = ops.now();
		List<UUID> roundIds = jdbc.sql("""
				SELECT id FROM task_review_rounds WHERE project_id = :p AND cycle_id = :c AND effective_plan_id IS NOT NULL
				""").param("p", projectId).param("c", cycleId).query(UUID.class).list();
		Map<UUID, Ctx> ctxs = reader.loadAll(roundIds);
		for (Ctx c : ctxs.values()) {
			TaskReader.Plan p = c.effective();
			Eval e = TaskReader.eval(c, p.deadline(), cy.closesAt().plusMillis(1), cy.closesAt(), true);
			UUID subAtCutoff = c.sub() != null && !c.sub().submittedAt().isAfter(cy.closesAt()) ? c.sub().id() : null;
			jdbc.sql("""
					INSERT INTO checkpoint_task_snapshots (cycle_id, project_id, round_id, task_id, assignee_id, approved_plan_id,
					    title_snapshot, deadline_snapshot, difficulty_snapshot, submission_id_at_cutoff, execution_state_at_cutoff, frozen_at)
					VALUES (:c, :p, :r, :t, :a, :plan, :title, :dl, :df, :sub, :st, :at)
					ON CONFLICT (cycle_id, round_id) DO UPDATE SET approved_plan_id = EXCLUDED.approved_plan_id,
					    title_snapshot = EXCLUDED.title_snapshot, deadline_snapshot = EXCLUDED.deadline_snapshot,
					    difficulty_snapshot = EXCLUDED.difficulty_snapshot, submission_id_at_cutoff = EXCLUDED.submission_id_at_cutoff,
					    execution_state_at_cutoff = EXCLUDED.execution_state_at_cutoff, frozen_at = EXCLUDED.frozen_at,
					    assignee_id = CASE WHEN EXISTS (SELECT 1 FROM checkpoint_report_items i
					                                     WHERE i.snapshot_id = checkpoint_task_snapshots.id)
					                       THEN checkpoint_task_snapshots.assignee_id ELSE EXCLUDED.assignee_id END
					WHERE checkpoint_task_snapshots.frozen_at IS NULL
					""").param("c", cycleId).param("p", projectId).param("r", c.round().id()).param("t", c.round().taskId())
					.param("a", p.assigneeId()).param("plan", p.id()).param("title", p.title()).param("dl", DbTime.toDb(p.deadline()))
					.param("df", p.difficulty()).param("sub", subAtCutoff).param("st", e.taskStatus())
					.param("at", DbTime.toDb(now)).update();
			String st = c.round().state();
			if ("ACTIVE".equals(st) || "AWAITING_REVIEW".equals(st)) {
				String next = c.sub() == null ? "CARRYOVER_REQUIRED" : "AWAITING_REVIEW";
				if (!next.equals(st)) {
					jdbc.sql("UPDATE task_review_rounds SET state = :s, version = version + 1 WHERE id = :r").param("s", next)
							.param("r", c.round().id()).update();
				}
			}
		}
		// người tham gia kỳ + đánh giá tại giờ chốt (bất biến)
		List<Row> rows = data.rows(s, cy);
		Reports rep = data.reports(cy);
		Map<UUID, CycleData.Dropout> dropouts = data.dropouts(projectId);
		for (UUID member : access.activeMemberIds(projectId)) {
			List<Row> mine = rows.stream().filter(r -> r.assigneeId().equals(member)).toList();
			Map<String, Object> eval = data.evaluation(s, cy, mine, rep, member, dropouts.get(member), true);
			jdbc.sql("""
					INSERT INTO checkpoint_participants (cycle_id, project_id, member_id) VALUES (:c, :p, :m)
					ON CONFLICT (cycle_id, member_id) DO NOTHING
					""").param("c", cycleId).param("p", projectId).param("m", member).update();
			jdbc.sql("""
					UPDATE checkpoint_participants SET role_at_cutoff = (SELECT role FROM project_members WHERE project_id = :p AND user_id = :m),
					       required_task_count = :n, report_id_at_cutoff = :rid, cutoff_summary = CAST(:j AS jsonb), frozen_at = :at
					 WHERE cycle_id = :c AND member_id = :m AND frozen_at IS NULL
					""").param("p", projectId).param("m", member).param("n", mine.size()).param("rid", rep.reportOf().get(member))
					.param("j", mapper.writeValueAsString(eval)).param("at", DbTime.toDb(now)).param("c", cycleId).update();
		}
		Map<String, Object> summary = compute(s, cy, true);
		jdbc.sql("""
				INSERT INTO checkpoint_cycle_summaries (cycle_id, project_id, summary, frozen_at)
				VALUES (:c, :p, CAST(:j AS jsonb), :at)
				""").param("c", cycleId).param("p", projectId).param("j", mapper.writeValueAsString(summary))
				.param("at", DbTime.toDb(now)).update();
		UUID lead = s.leaderId();
		if (lead != null) {
			ops.notify(projectId, "CHECKPOINT_REVIEW_REQUIRED", "CHECKPOINT_CYCLE", cycleId, cycleId,
					"checkpoint:" + cycleId + ":review-required", List.of(lead), null,
					"Kỳ " + cy.sequenceNo() + " đã chốt — mời bạn kiểm tra", (String) summary.get("headline"),
					J.o("groupId", projectId, "cycleId", cycleId));
		}
		finalizeIfDone(cycleId);
	}

	// ------------------------------------------------------------------ LeadCycleSummary
	@Transactional(readOnly = true)
	public Map<String, Object> leadSummary(UUID projectId, UUID cycleId, boolean atCutoff) {
		Scope s = scopes.of(projectId);
		CycleRow cy = s.cycleOpt(cycleId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint."));
		if (atCutoff) {
			String json = jdbc.sql("SELECT summary::text FROM checkpoint_cycle_summaries WHERE cycle_id = :c").param("c", cycleId)
					.query(String.class).optional().orElseThrow(() -> ApiException.invalidState("Kỳ chưa chốt nên chưa có bản chụp tại giờ chốt."));
			@SuppressWarnings("unchecked")
			Map<String, Object> m = mapper.readValue(json, Map.class);
			return m;
		}
		return compute(s, cy, false);
	}

	Map<String, Object> compute(Scope s, CycleRow cy, boolean cutoff) {
		List<Row> rows = data.rows(s, cy);
		Reports rep = data.reports(cy);
		List<UUID> members = access.activeMemberIds(s.projectId());
		List<Map<String, Object>> notResponded = new ArrayList<>();
		int reported = 0;
		for (UUID m : members) {
			if (rep.reportOf().containsKey(m)) {
				reported++;
			}
			else {
				notResponded.add(s.user(m));
			}
		}
		int accepted = 0;
		int pending = 0;
		int late = 0;
		int rework = 0;
		int noResult = 0;
		int carry = 0;
		for (Row r : rows) {
			Eval e = cutoff ? data.cutoffEval(cy, r) : data.eval(s, cy, r);
			if ("DONE".equals(e.taskStatus())) {
				accepted++;
			}
			if ("SUBMITTED".equals(e.taskStatus())) {
				pending++;
			}
			if (e.isLate()) {
				late++;
			}
			if ("NEEDS_REWORK".equals(e.timeliness())) {
				rework++;
			}
			if ("OVERDUE_NO_RESULT".equals(e.timeliness())) {
				noResult++;
			}
			if (e.needsCarryover() || (cutoff && "OVERDUE_NO_RESULT".equals(e.timeliness()))) {
				carry++;
			}
		}
		long risks = count("SELECT count(*) FROM risk_reports WHERE project_id = :p AND status <> 'RESOLVED'", s.projectId());
		long support = count("SELECT count(*) FROM support_requests WHERE project_id = :p AND status <> 'RESOLVED'", s.projectId());
		long help = count("SELECT count(*) FROM help_requests WHERE project_id = :p AND status = 'OPEN'", s.projectId());
		String phase = cutoff ? "AWAITING_LEAD_REVIEW" : cy.phase(s.now());
		boolean closed = cutoff || s.now().isAfter(cy.closesAt());
		String headline = "Kỳ " + cy.sequenceNo() + (closed ? " đã chốt: " : " đang diễn ra: ") + reported + "/" + members.size()
				+ " thành viên đã báo cáo, " + pending + " task chờ bạn duyệt, " + noResult + " task chưa có kết quả.";
		return J.o("cycleId", cy.id(), "phase", phase, "asOf", cutoff ? ops.now() : s.now(), "isAtCutoff", cutoff,
				"members", J.o("total", members.size(), "reported", reported, "notResponded", members.size() - reported),
				"tasks", J.o("total", rows.size(), "accepted", accepted, "submittedPendingReview", pending, "late", late,
						"needsRework", rework, "noResult", noResult, "carryoverRequired", carry),
				"openRisks", risks, "openPrivateSupportRequests", support, "openHelpRequests", help,
				"notRespondedMembers", notResponded, "headline", headline);
	}

	private long count(String sql, UUID projectId) {
		return jdbc.sql(sql).param("p", projectId).query(Long.class).single();
	}

	// ------------------------------------------------------------------ chốt đánh giá kỳ
	/** CLOSED → FINALIZED khi mọi task có quyết định và việc cần chuyển kỳ đã có deadline TL chốt. */
	@Transactional
	public boolean finalizeIfDone(UUID cycleId) {
		Object[] row = jdbc.sql("SELECT project_id, status, closes_at FROM checkpoint_cycles WHERE id = :c FOR UPDATE")
				.param("c", cycleId).query((rs, i) -> new Object[] { J.uuid(rs, "project_id"), rs.getString("status"),
						J.inst(rs, "closes_at") }).optional().orElse(null);
		if (row == null) {
			return false;
		}
		if ("FINALIZED".equals(row[1])) {
			return true;
		}
		Instant now = ops.now();
		if (!"CLOSED".equals(row[1]) || !now.isAfter((Instant) row[2])) {
			return false;
		}
		long pending = jdbc.sql("""
				SELECT count(*) FROM task_review_rounds WHERE cycle_id = :c AND effective_plan_id IS NOT NULL
				   AND state IN ('ACTIVE', 'AWAITING_REVIEW', 'CARRYOVER_REQUIRED')
				""").param("c", cycleId).query(Long.class).single();
		if (pending > 0) {
			return false;
		}
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'FINALIZED', finalized_at = :at, version = version + 1 WHERE id = :c")
				.param("at", DbTime.toDb(now)).param("c", cycleId).update();
		UUID projectId = (UUID) row[0];
		Scope s = scopes.of(projectId);
		CycleRow cy = s.cycle(cycleId);
		List<Row> rows = data.rows(s, cy);
		Reports rep = data.reports(cy);
		Map<UUID, CycleData.Dropout> dropouts = data.dropouts(projectId);
		List<UUID> participants = jdbc.sql("SELECT member_id FROM checkpoint_participants WHERE cycle_id = :c").param("c", cycleId)
				.query(UUID.class).list();
		for (UUID member : participants) {
			List<Row> mine = rows.stream().filter(r -> r.assigneeId().equals(member)).toList();
			Map<String, Object> eval = data.evaluation(s, cy, mine, rep, member, dropouts.get(member), false);
			jdbc.sql("UPDATE checkpoint_participants SET final_summary = CAST(:j AS jsonb), finalized_at = :at WHERE cycle_id = :c AND member_id = :m")
					.param("j", mapper.writeValueAsString(eval)).param("at", DbTime.toDb(now)).param("c", cycleId).param("m", member).update();
		}
		return true;
	}
}
