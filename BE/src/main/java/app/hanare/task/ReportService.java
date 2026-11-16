package app.hanare.task;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.task.CycleData.Row;
import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Eval;
import app.hanare.task.TaskReader.Sub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Mục H: báo cáo checkpoint hàng loạt điền sẵn (my-report, lưu nháp, gửi). */
@Service
public class ReportService {

	public record ItemInput(UUID taskId, String declaredResult, String riskLevel, String note, Instant expectedCompletionAt) {
	}

	public record SaveReportRequest(List<ItemInput> items, Long version) {
	}

	public record SubmitReportRequest(List<ItemInput> items, Long version) {
	}

	private record Rep(UUID id, UUID participantId, int revisionNo, String status, Instant submittedAt, long version) {
	}

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Scope.Factory scopes;
	private final TaskReader reader;
	private final CycleData data;
	private final TaskViews views;
	private final Ops ops;
	private final TransactionTemplate tx;

	public ReportService(JdbcClient jdbc, GroupAccess access, Scope.Factory scopes, TaskReader reader, CycleData data,
			TaskViews views, Ops ops, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.access = access;
		this.scopes = scopes;
		this.reader = reader;
		this.data = data;
		this.views = views;
		this.ops = ops;
		this.tx = tx;
	}

	private List<Rep> reports(UUID cycleId, UUID member) {
		return jdbc.sql("""
				SELECT rep.id, rep.participant_id, rep.revision_no, rep.status, rep.submitted_at, rep.version
				  FROM checkpoint_reports rep JOIN checkpoint_participants p ON p.id = rep.participant_id
				 WHERE p.cycle_id = :c AND p.member_id = :m ORDER BY rep.revision_no DESC
				""").param("c", cycleId).param("m", member).query((rs, i) -> new Rep(J.uuid(rs, "id"), J.uuid(rs, "participant_id"),
				rs.getInt("revision_no"), rs.getString("status"), J.inst(rs, "submitted_at"), rs.getLong("version"))).list();
	}

	private static Rep draft(List<Rep> l) {
		return l.stream().filter(r -> "DRAFT".equals(r.status())).findFirst().orElse(null);
	}

	private static Rep lastSubmitted(List<Rep> l) {
		return l.stream().filter(r -> "SUBMITTED".equals(r.status())).findFirst().orElse(null);
	}

	private Map<UUID, CycleData.ReportItem> storedItems(UUID reportId) {
		Map<UUID, CycleData.ReportItem> m = new HashMap<>();
		if (reportId != null) {
			jdbc.sql("""
					SELECT round_id, declared_result, risk_status, needs_lead_help, note, expected_completion_at
					  FROM checkpoint_report_items WHERE report_id = :r
					""").param("r", reportId).query((rs, i) -> {
				m.put(J.uuid(rs, "round_id"), new CycleData.ReportItem(J.uuid(rs, "round_id"), rs.getString("declared_result"),
						rs.getString("risk_status"), rs.getBoolean("needs_lead_help"), rs.getString("note"),
						J.inst(rs, "expected_completion_at")));
				return 0;
			}).list();
		}
		return m;
	}

	private List<Row> required(Scope s, CycleRow cy, UUID member) {
		return data.rows(s, cy).stream().filter(r -> r.assigneeId().equals(member)).toList();
	}

	// ------------------------------------------------------------------ GET my-report
	public Map<String, Object> view(UUID projectId, UUID cycleId, CurrentUser user) {
		access.requireMember(projectId, user);
		return tx.execute(st -> build(scopes.of(projectId), cycleId, user.id()));
	}

	private Map<String, Object> build(Scope s, UUID cycleId, UUID me) {
		CycleRow cy = s.cycleOpt(cycleId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint."));
		Instant now = s.now();
		List<Row> required = required(s, cy, me);
		List<Rep> reps = reports(cycleId, me);
		Rep dr = draft(reps);
		Rep cur = dr != null ? dr : lastSubmitted(reps);
		Map<UUID, CycleData.ReportItem> stored = storedItems(cur == null ? null : cur.id());
		List<Map<String, Object>> items = new ArrayList<>();
		List<UUID> missing = new ArrayList<>();
		for (Row r : required) {
			Ctx c = r.ctx();
			Eval e = data.eval(s, cy, r);
			CycleData.ReportItem it = stored.get(r.roundId());
			Sub sub = c.sub();
			Map<String, Object> brief = sub == null ? null : J.o("id", sub.id(), "submissionNo", sub.no(), "submittedAt", sub.submittedAt(),
					"isOnTime", sub.planDeadline() == null || !sub.submittedAt().isAfter(sub.planDeadline()),
					"evidence", views.evidence(sub.evidenceJson()), "reviewDecision", c.rev() == null ? null : c.rev().decision());
			if (it == null) {
				missing.add(r.taskId());
			}
			items.add(J.o("taskId", r.taskId(), "roundId", r.roundId(), "roundNo", c.round().roundNo(), "title", r.title(),
					"expectedOutput", c.effective().expectedOutput(), "deadline", r.deadline(), "difficulty", r.difficulty(),
					"taskStatus", e.taskStatus(), "timeliness", e.timeliness(), "timelinessLabel", e.label(), "isLate", e.isLate(),
					"submission", brief, "declaredResult", it != null ? it.declared() : sub != null ? "RESULT_SUBMITTED" : "RESULT_MISSING",
					"prefilled", it == null, "riskLevel", it != null ? it.riskLevel() : "ON_TRACK", "note", it == null ? null : it.note(),
					"expectedCompletionAt", it == null ? null : it.expectedAt()));
		}
		boolean complete = missing.isEmpty();
		String state = dr != null ? "DRAFT" : cur != null ? "SUBMITTED" : "NOT_RESPONDED";
		boolean statusOpen = "SCHEDULED".equals(cy.status()) || "OPEN".equals(cy.status());
		Map<String, Object> lock = null;
		if (now.isBefore(cy.opensAt())) {
			lock = J.o("code", "REPORT_WINDOW_NOT_OPEN", "message", "Chưa đến thời gian mở báo cáo của kỳ này.");
		}
		else if (now.isAfter(cy.closesAt()) || !statusOpen) {
			lock = J.o("code", "CYCLE_CLOSED", "message", "Kỳ đã đóng — báo cáo đã bị khóa.");
		}
		return J.o("serverTime", now, "cycle", s.cycleDto(cy), "state", state, "revisionNo", cur == null ? null : cur.revisionNo(),
				"status", cur == null ? null : cur.status(), "submittedAt", cur == null ? null : cur.submittedAt(),
				"canEdit", lock == null, "lockReason", lock, "items", items, "backlog", backlog(s, cy, me),
				"completeness", J.o("requiredCount", required.size(), "respondedCount", required.size() - missing.size(),
						"missingTaskIds", missing, "isComplete", complete),
				"submittedLabel", cur != null && "SUBMITTED".equals(cur.status()) && complete ? "Đã gửi đầy đủ" : null,
				"version", cur == null ? null : cur.version());
	}

	private List<Map<String, Object>> backlog(Scope s, CycleRow cy, UUID me) {
		List<UUID> ids = jdbc.sql("""
				SELECT r.id FROM task_review_rounds r
				  JOIN task_plan_revisions p ON p.id = r.effective_plan_id
				  JOIN checkpoint_cycles pc ON pc.id = r.cycle_id
				 WHERE r.project_id = :p AND p.assignee_id = :m AND pc.closes_at <= :start
				   AND r.state IN ('ACTIVE', 'AWAITING_REVIEW', 'CARRYOVER_REQUIRED')
				""").param("p", s.projectId()).param("m", me).param("start", DbTime.toDb(cy.periodStartAt())).query(UUID.class).list();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Ctx c : reader.loadAll(ids).values()) {
			CycleRow from = s.cycle(c.round().cycleId());
			Eval e = TaskReader.eval(c, c.effective().deadline(), s.now(), from.closesAt(), false);
			boolean carry = e.needsCarryover();
			boolean waiting = c.sub() != null && c.rev() == null;
			if (!carry && !waiting) {
				continue;
			}
			out.add(J.o("taskId", c.round().taskId(), "roundId", c.round().id(), "title", c.effective().title(),
					"fromCycle", s.cycleRef(from), "state", carry ? "CARRYOVER_REQUIRED" : "AWAITING_REVIEW_PREVIOUS_CYCLE",
					"message", carry ? "Cần TL chốt lịch xử lý tiếp" : "Chờ TL duyệt ở kỳ trước"));
		}
		return out;
	}

	// ------------------------------------------------------------------ PUT my-report (nháp)
	public Map<String, Object> save(UUID projectId, UUID cycleId, CurrentUser user, SaveReportRequest r) {
		access.requireMember(projectId, user);
		if (r.items() == null) {
			throw ApiException.validation("items", "Bắt buộc");
		}
		return tx.execute(st -> {
			writeDraft(projectId, cycleId, user.id(), r.items(), r.version(), true);
			return build(scopes.of(projectId), cycleId, user.id());
		});
	}

	// ------------------------------------------------------------------ POST my-report/submit
	public Map<String, Object> submit(UUID projectId, UUID cycleId, CurrentUser user, SubmitReportRequest r) {
		access.requireMember(projectId, user);
		// bước 1 (commit): lưu nháp từ items gửi lên / điền sẵn, để REPORT_INCOMPLETE vẫn giữ nháp
		List<UUID> missing = tx.execute(st -> writeDraft(projectId, cycleId, user.id(), r.items(), r.version(), false));
		if (missing == null) {
			return tx.execute(st -> build(scopes.of(projectId), cycleId, user.id())); // đã gửi, lặp lại: không đổi
		}
		if (!missing.isEmpty()) {
			List<ApiError.FieldError> fe = missing.stream().map(id -> new ApiError.FieldError("items[" + id + "]", "Chưa khai báo")).toList();
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPORT_INCOMPLETE",
					"Còn " + missing.size() + " task bắt buộc chưa khai báo. Bản nháp đã được lưu.", fe);
		}
		return tx.execute(st -> {
			jdbc.sql("SELECT id FROM checkpoint_cycles WHERE id = :c FOR SHARE").param("c", cycleId).query(UUID.class).list();
			Scope s = scopes.of(projectId);
			checkWindow(s.cycleOpt(cycleId).orElseThrow(), ops.now());
			Rep dr = draft(reports(cycleId, user.id()));
			if (dr == null) {
				return build(s, cycleId, user.id());
			}
			Instant now = ops.now();
			jdbc.sql("UPDATE checkpoint_reports SET status = 'SUBMITTED', submitted_at = :at, version = version + 1 WHERE id = :id")
					.param("at", DbTime.toDb(now)).param("id", dr.id()).update();
			ops.audit(projectId, user.id(), "CHECKPOINT_REPORT", dr.id(), "REPORT_SUBMITTED", null,
					J.o("cycleId", cycleId, "revisionNo", dr.revisionNo()));
			return build(scopes.of(projectId), cycleId, user.id());
		});
	}

	private void checkWindow(CycleRow cy, Instant now) {
		if (now.isBefore(cy.opensAt())) {
			throw ApiException.of(HttpStatus.CONFLICT, "REPORT_WINDOW_NOT_OPEN", "Chưa đến thời gian mở báo cáo của kỳ này.");
		}
		if (now.isAfter(cy.closesAt()) || !("SCHEDULED".equals(cy.status()) || "OPEN".equals(cy.status()))) {
			throw ApiException.of(HttpStatus.CONFLICT, "CYCLE_CLOSED", "Kỳ đã đóng — báo cáo đã bị khóa.");
		}
	}

	/**
	 * Ghi bản nháp. {@code failIfNoItems=true} (PUT): items bắt buộc. Trả danh sách task bắt buộc còn thiếu;
	 * trả {@code null} khi báo cáo đã gửi và không có gì để làm (gửi lặp lại).
	 */
	private List<UUID> writeDraft(UUID projectId, UUID cycleId, UUID me, List<ItemInput> input, Long version, boolean put) {
		jdbc.sql("SELECT id FROM checkpoint_cycles WHERE id = :c FOR SHARE").param("c", cycleId).query(UUID.class).list();
		Scope s = scopes.of(projectId);
		CycleRow cy = s.cycleOpt(cycleId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint."));
		Instant now = ops.now();
		checkWindow(cy, now);
		List<Row> required = required(s, cy, me);
		List<Rep> reps = reports(cycleId, me);
		Rep dr = draft(reps);
		Rep sub = lastSubmitted(reps);
		Rep base = dr != null ? dr : sub;
		if (base == null ? version != null : (version == null || version != base.version())) {
			throw ApiException.versionConflict();
		}
		if (input == null && dr == null && sub != null) {
			return null;
		}
		// danh sách khai báo cần ghi
		List<ItemInput> items;
		if (input != null) {
			items = input;
		}
		else if (dr != null) {
			return missing(required, storedItems(dr.id()));
		}
		else {
			items = new ArrayList<>();
			for (Row r : required) {
				items.add(new ItemInput(r.taskId(), r.ctx().sub() != null ? "RESULT_SUBMITTED" : "RESULT_MISSING", "ON_TRACK", null, null));
			}
		}
		Map<UUID, Row> byTask = new HashMap<>();
		required.forEach(r -> byTask.put(r.taskId(), r));
		J.Check ck = J.check();
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < items.size(); i++) {
			ItemInput it = items.get(i);
			String f = "items[" + i + "]";
			if (it == null || it.taskId() == null || !byTask.containsKey(it.taskId())) {
				ck.add(f + ".taskId", "Task không thuộc phạm vi báo cáo bắt buộc của bạn trong kỳ này");
				continue;
			}
			if (!seen.add(it.taskId())) {
				ck.add(f + ".taskId", "Trùng task");
			}
			ck.require(List.of("RESULT_SUBMITTED", "RESULT_MISSING").contains(it.declaredResult()), f + ".declaredResult", "Giá trị không hợp lệ");
			ck.require(List.of("ON_TRACK", "AT_RISK", "NEEDS_LEAD_HELP").contains(it.riskLevel()), f + ".riskLevel", "Giá trị không hợp lệ");
			if (it.note() != null && it.note().length() > 1000) {
				ck.add(f + ".note", "Tối đa 1000 ký tự");
			}
		}
		ck.done();
		for (int i = 0; i < items.size(); i++) {
			ItemInput it = items.get(i);
			boolean hasSub = byTask.get(it.taskId()).ctx().sub() != null;
			if (("RESULT_SUBMITTED".equals(it.declaredResult()) && !hasSub) || ("RESULT_MISSING".equals(it.declaredResult()) && hasSub)) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESULT_DECLARATION_MISMATCH",
						"Khai báo kết quả không khớp với lần nộp thực tế của task.",
						List.of(new ApiError.FieldError("items[" + i + "].declaredResult", "Không khớp lần nộp thật")));
			}
		}
		UUID participant = jdbc.sql("""
				INSERT INTO checkpoint_participants (cycle_id, project_id, member_id) VALUES (:c, :p, :m)
				ON CONFLICT (cycle_id, member_id) DO UPDATE SET cycle_id = EXCLUDED.cycle_id RETURNING id
				""").param("c", cycleId).param("p", projectId).param("m", me).query(UUID.class).single();
		UUID reportId;
		if (dr == null) {
			int no = reps.isEmpty() ? 1 : reps.get(0).revisionNo() + 1;
			reportId = jdbc.sql("""
					INSERT INTO checkpoint_reports (participant_id, cycle_id, member_id, revision_no, status, created_at)
					VALUES (:pt, :c, :m, :no, 'DRAFT', :at) RETURNING id
					""").param("pt", participant).param("c", cycleId).param("m", me).param("no", no).param("at", DbTime.toDb(now))
					.query(UUID.class).single();
		}
		else {
			reportId = dr.id();
			jdbc.sql("UPDATE checkpoint_reports SET version = version + 1 WHERE id = :id").param("id", reportId).update();
			jdbc.sql("DELETE FROM checkpoint_report_items WHERE report_id = :id").param("id", reportId).update();
		}
		for (ItemInput it : items) {
			Row row = byTask.get(it.taskId());
			Ctx c = row.ctx();
			TaskReader.Plan p = c.effective();
			UUID snapshot = jdbc.sql("""
					INSERT INTO checkpoint_task_snapshots (cycle_id, project_id, round_id, task_id, assignee_id, approved_plan_id,
					    title_snapshot, deadline_snapshot, difficulty_snapshot, submission_id_at_cutoff, execution_state_at_cutoff)
					VALUES (:c, :p, :r, :t, :a, :plan, :title, :dl, :df, :sub, :st)
					ON CONFLICT (cycle_id, round_id) DO UPDATE SET cycle_id = EXCLUDED.cycle_id RETURNING id
					""").param("c", cycleId).param("p", projectId).param("r", row.roundId()).param("t", row.taskId())
					.param("a", row.assigneeId()).param("plan", p.id()).param("title", p.title()).param("dl", DbTime.toDb(p.deadline()))
					.param("df", p.difficulty()).param("sub", c.sub() == null ? null : c.sub().id())
					.param("st", TaskReader.eval(c, p.deadline(), now, cy.closesAt(), false).taskStatus()).query(UUID.class).single();
			boolean help = "NEEDS_LEAD_HELP".equals(it.riskLevel());
			jdbc.sql("""
					INSERT INTO checkpoint_report_items (report_id, cycle_id, member_id, snapshot_id, round_id, declared_result, note,
					    risk_status, needs_lead_help, submission_id, expected_completion_at)
					VALUES (:rep, :c, :m, :snap, :r, :dec, :note, :risk, :help, :sub, :ec)
					""").param("rep", reportId).param("c", cycleId).param("m", me).param("snap", snapshot).param("r", row.roundId())
					.param("dec", it.declaredResult()).param("note", it.note() == null || it.note().isBlank() ? null : it.note().strip())
					.param("risk", "ON_TRACK".equals(it.riskLevel()) ? "NORMAL" : "AT_RISK").param("help", help)
					.param("sub", "RESULT_SUBMITTED".equals(it.declaredResult()) ? c.sub().id() : null)
					.param("ec", DbTime.toDb(it.expectedCompletionAt())).update();
		}
		return missing(required, storedItems(reportId));
	}

	private List<UUID> missing(List<Row> required, Map<UUID, CycleData.ReportItem> stored) {
		return required.stream().filter(r -> !stored.containsKey(r.roundId())).map(Row::taskId).toList();
	}
}
