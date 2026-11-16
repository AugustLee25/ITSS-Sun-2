package app.hanare.task;

import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Eval;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Dữ liệu theo kỳ: các task của kỳ (từ ảnh chụp tại giờ chốt nếu đã chụp, ngược lại từ vòng xử lý hiện hành),
 * báo cáo checkpoint đã gửi, và đánh giá màu khung tên (spec 0.6, API_CONTRACT 4.2).
 */
@Component
public class CycleData {

	/** Một task trong một kỳ. {@code assigneeId} = người thực hiện trong kỳ đó (theo snapshot khi đã chốt). */
	public record Row(UUID roundId, UUID taskId, UUID assigneeId, String title, Instant deadline, String difficulty,
			UUID snapshotId, Ctx ctx) {
	}

	public record ReportItem(UUID roundId, String declared, String risk, boolean needsHelp, String note, Instant expectedAt) {
		public String riskLevel() {
			return needsHelp ? "NEEDS_LEAD_HELP" : ("AT_RISK".equals(risk) || "BLOCKED".equals(risk)) ? "AT_RISK" : "ON_TRACK";
		}
	}

	/** Báo cáo gửi (SUBMITTED, ≤ giờ chốt) mới nhất của từng thành viên + ai đang có nháp. */
	public record Reports(Map<UUID, UUID> reportOf, Map<UUID, Instant> submittedAt, Map<UUID, Map<UUID, ReportItem>> items,
			Set<UUID> drafts) {
		public String state(UUID member) {
			return reportOf.containsKey(member) ? "SUBMITTED" : drafts.contains(member) ? "DRAFT" : "NOT_RESPONDED";
		}

		public ReportItem item(UUID member, UUID roundId) {
			Map<UUID, ReportItem> m = items.get(reportOf.get(member));
			return m == null ? null : m.get(roundId);
		}
	}

	public record Dropout(UUID riskId, UUID subject, UUID confirmedBy, Instant confirmedAt, String summary) {
	}

	private final JdbcClient jdbc;
	private final TaskReader reader;

	public CycleData(JdbcClient jdbc, TaskReader reader) {
		this.jdbc = jdbc;
		this.reader = reader;
	}

	public boolean frozen(UUID cycleId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM checkpoint_cycle_summaries WHERE cycle_id = :c)").param("c", cycleId)
				.query(Boolean.class).single();
	}

	// ------------------------------------------------------------------ task của kỳ
	public List<Row> rows(Scope s, CycleRow cy) {
		List<Row> out = new ArrayList<>();
		if (frozen(cy.id())) {
			List<Object[]> snaps = new ArrayList<>();
			jdbc.sql("""
					SELECT id, round_id, task_id, assignee_id, title_snapshot, deadline_snapshot, difficulty_snapshot
					  FROM checkpoint_task_snapshots WHERE cycle_id = :c AND frozen_at IS NOT NULL ORDER BY deadline_snapshot, id
					""").param("c", cy.id()).query((rs, i) -> {
				snaps.add(new Object[] { J.uuid(rs, "id"), J.uuid(rs, "round_id"), J.uuid(rs, "task_id"),
						J.uuid(rs, "assignee_id"), rs.getString("title_snapshot"), J.inst(rs, "deadline_snapshot"),
						rs.getString("difficulty_snapshot") });
				return 0;
			}).list();
			Map<UUID, Ctx> ctx = reader.loadAll(snaps.stream().map(a -> (UUID) a[1]).toList());
			for (Object[] a : snaps) {
				out.add(new Row((UUID) a[1], (UUID) a[2], (UUID) a[3], (String) a[4], (Instant) a[5], (String) a[6], (UUID) a[0],
						ctx.get((UUID) a[1])));
			}
			return out;
		}
		List<UUID> ids = jdbc.sql("""
				SELECT r.id FROM task_review_rounds r JOIN task_plan_revisions p ON p.id = r.effective_plan_id
				 WHERE r.project_id = :p AND r.cycle_id = :c ORDER BY p.deadline, r.id
				""").param("p", s.projectId()).param("c", cy.id()).query(UUID.class).list();
		Map<UUID, Ctx> ctx = reader.loadAll(ids);
		for (UUID id : ids) {
			Ctx c = ctx.get(id);
			out.add(new Row(id, c.round().taskId(), c.effective().assigneeId(), c.effective().title(), c.effective().deadline(),
					c.effective().difficulty(), null, c));
		}
		return out;
	}

	public Eval eval(Scope s, CycleRow cy, Row r) {
		return TaskReader.eval(r.ctx(), r.deadline(), s.now(), cy.closesAt(), false);
	}

	/** Đánh giá "tại giờ chốt": bỏ mọi quyết định duyệt (chỉ xảy ra sau giờ chốt). */
	public Eval cutoffEval(CycleRow cy, Row r) {
		return TaskReader.eval(r.ctx(), r.deadline(), cy.closesAt().plusMillis(1), cy.closesAt(), true);
	}

	// ------------------------------------------------------------------ báo cáo
	public Reports reports(CycleRow cy) {
		Map<UUID, UUID> reportOf = new HashMap<>();
		Map<UUID, Instant> at = new HashMap<>();
		jdbc.sql("""
				SELECT DISTINCT ON (p.member_id) rep.id, p.member_id, rep.submitted_at
				  FROM checkpoint_reports rep JOIN checkpoint_participants p ON p.id = rep.participant_id
				 WHERE p.cycle_id = :c AND rep.status = 'SUBMITTED' AND rep.submitted_at <= :close
				 ORDER BY p.member_id, rep.revision_no DESC
				""").param("c", cy.id()).param("close", app.hanare.common.DbTime.toDb(cy.closesAt())).query((rs, i) -> {
			reportOf.put(J.uuid(rs, "member_id"), J.uuid(rs, "id"));
			at.put(J.uuid(rs, "member_id"), J.inst(rs, "submitted_at"));
			return 0;
		}).list();
		Map<UUID, Map<UUID, ReportItem>> items = new HashMap<>();
		if (!reportOf.isEmpty()) {
			jdbc.sql("""
					SELECT report_id, round_id, declared_result, risk_status, needs_lead_help, note, expected_completion_at
					  FROM checkpoint_report_items WHERE report_id IN (:ids)
					""").param("ids", reportOf.values()).query((rs, i) -> {
				items.computeIfAbsent(J.uuid(rs, "report_id"), k -> new HashMap<>()).put(J.uuid(rs, "round_id"),
						new ReportItem(J.uuid(rs, "round_id"), rs.getString("declared_result"), rs.getString("risk_status"),
								rs.getBoolean("needs_lead_help"), rs.getString("note"), J.inst(rs, "expected_completion_at")));
				return 0;
			}).list();
		}
		Set<UUID> drafts = new HashSet<>(jdbc.sql("""
				SELECT p.member_id FROM checkpoint_reports rep JOIN checkpoint_participants p ON p.id = rep.participant_id
				 WHERE p.cycle_id = :c AND rep.status = 'DRAFT'
				""").param("c", cy.id()).query(UUID.class).list());
		return new Reports(reportOf, at, items, drafts);
	}

	public Map<UUID, Dropout> dropouts(UUID projectId) {
		Map<UUID, Dropout> m = new HashMap<>();
		jdbc.sql("""
				SELECT id, subject_member_id, confirmed_by, confirmed_at, public_summary FROM risk_reports
				 WHERE project_id = :p AND risk_type = 'DROPOUT_RISK' AND status = 'CONFIRMED'
				""").param("p", projectId).query((rs, i) -> {
			m.put(J.uuid(rs, "subject_member_id"), new Dropout(J.uuid(rs, "id"), J.uuid(rs, "subject_member_id"),
					J.uuid(rs, "confirmed_by"), J.inst(rs, "confirmed_at"), rs.getString("public_summary")));
			return 0;
		}).list();
		return m;
	}

	// ------------------------------------------------------------------ đánh giá màu khung tên (4.2)
	public Map<String, Object> evaluation(Scope s, CycleRow cy, List<Row> memberRows, Reports reports, UUID member,
			Dropout dropout, boolean cutoff) {
		boolean closed = cutoff || s.now().isAfter(cy.closesAt());
		int total = memberRows.size();
		int due = 0;
		int accepted = 0;
		int pending = 0;
		int late = 0;
		int rework = 0;
		int noResult = 0;
		boolean allAcceptedOnTime = true;
		for (Row r : memberRows) {
			Eval e = cutoff ? cutoffEval(cy, r) : eval(s, cy, r);
			boolean isDue = closed || !r.deadline().isAfter(s.now());
			if (!isDue) {
				continue;
			}
			due++;
			if ("DONE".equals(e.taskStatus())) {
				accepted++;
			}
			if ("ON_TIME_PENDING_REVIEW".equals(e.timeliness()) || "LATE_PENDING_REVIEW".equals(e.timeliness())) {
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
			if (!"ON_TIME_ACCEPTED".equals(e.timeliness())) {
				allAcceptedOnTime = false;
			}
		}
		int reported = 0;
		for (Row r : memberRows) {
			if (reports.item(member, r.roundId()) != null) {
				reported++;
			}
		}
		String phase = cutoff ? "AWAITING_LEAD_REVIEW" : cy.phase(s.now());
		String color;
		String code;
		String label;
		String reason;
		boolean provisional;
		if (dropout != null) {
			color = "RED";
			code = "DROPOUT_RISK_CONFIRMED";
			label = "Đỏ — TL xác nhận nguy cơ bỏ việc";
			reason = "TL " + s.name(dropout.confirmedBy()) + " đã xác nhận nguy cơ bỏ việc. Màu này không suy ra từ việc im lặng.";
		}
		else if (late >= 3) {
			color = "RED";
			code = "LATE_3_PLUS";
			label = "Trễ " + late + " task";
			reason = late + " task trễ trong " + due + " task đến hạn.";
		}
		else if (late >= 1) {
			color = "YELLOW";
			code = "LATE_1_2";
			label = "Trễ " + late + " task";
			reason = late + " task trễ trong " + due + " task đến hạn" + (rework > 0 ? ", " + rework + " task TL yêu cầu sửa/cập nhật." : ".");
		}
		else if (due == 0) {
			color = "GRAY";
			code = "NO_TASK_DUE";
			label = "Chưa có task đến hạn";
			reason = total == 0 ? "Chưa có task được duyệt trong kỳ này." : "Chưa có task nào đến hạn trong kỳ.";
		}
		else if (allAcceptedOnTime) {
			color = "GREEN";
			code = "ALL_ACCEPTED";
			label = "Đã xác nhận hoàn thành";
			reason = due + "/" + due + " task đến hạn đã được TL xác nhận đúng hạn.";
		}
		else if (pending > 0) {
			color = "GREEN";
			code = "ALL_SUBMITTED_PENDING_REVIEW";
			label = "Đã nộp đủ — chờ TL duyệt";
			reason = due + "/" + due + " task đến hạn đã nộp đúng hạn, " + pending + " task đang chờ TL duyệt.";
		}
		else {
			color = "GRAY";
			code = "INSUFFICIENT_DATA";
			label = "Chưa đủ dữ liệu";
			reason = "Chưa đủ dữ liệu để đánh giá.";
		}
		provisional = "ALL_SUBMITTED_PENDING_REVIEW".equals(code) || "TENTATIVE".equals(phase);
		Map<String, Object> counts = J.o("totalTasks", total, "dueTasks", due, "acceptedTasks", accepted, "pendingReviewTasks", pending,
				"lateTasks", late, "needsReworkTasks", rework, "noResultTasks", noResult, "reportedTasks", reported,
				"missingReportTasks", total - reported);
		return J.o("color", color, "colorLabel", switch (color) {
			case "GREEN" -> "Xanh";
			case "YELLOW" -> "Vàng";
			case "RED" -> "Đỏ";
			default -> "Xám";
		}, "statusLabel", label, "reasonCode", code, "reason", reason, "provisional", provisional, "phase", phase,
				"phaseLabel", CycleRow.phaseLabel(phase), "counts", counts);
	}

	// ------------------------------------------------------------------ CycleTaskRow
	public Map<String, Object> taskRow(Scope s, CycleRow cy, Row r, Reports reports, boolean showNote) {
		Ctx c = r.ctx();
		Eval e = eval(s, cy, r);
		UUID current = c.executor();
		boolean handed = current != null && !current.equals(r.assigneeId());
		var rev = c.rev();
		boolean completedLate = "ACCEPTED".equals(c.round().state()) && c.sub() != null && c.sub().planDeadline() != null
				&& c.sub().submittedAt().isAfter(c.sub().planDeadline());
		ReportItem it = reports.item(r.assigneeId(), r.roundId());
		Map<String, Object> carry = J.o("required", e.needsCarryover(), "reason", e.carryoverReason(), "targetRoundId", targetRound(r.roundId()));
		Map<String, Object> review = rev == null ? null : J.o("decision", rev.decision(), "reviewer", s.user(rev.reviewerId()),
				"reviewedAt", rev.reviewedAt(), "issueDescription", rev.issue());
		Map<String, Object> m = new LinkedHashMap<>();
		m.putAll(J.o("taskId", r.taskId(), "roundId", r.roundId(), "roundNo", c.round().roundNo(), "title", r.title(),
				"assignee", s.user(r.assigneeId()), "handedOverTo", handed ? s.user(current) : null, "deadline", r.deadline(),
				"difficulty", r.difficulty(), "taskStatus", e.taskStatus(), "timeliness", e.timeliness(),
				"timelinessLabel", e.label(), "isLate", e.isLate(), "submittedAt", c.sub() == null ? null : c.sub().submittedAt(),
				"review", review, "completedAt", c.round().completedAt(), "acceptedAt", c.round().acceptedAt(),
				"completedLate", completedLate, "reported", it != null, "declaredResult", it == null ? null : it.declared(),
				"riskLevel", it == null ? null : it.riskLevel(), "reportNote", showNote && it != null ? it.note() : null,
				"carryover", carry));
		return m;
	}

	private UUID targetRound(UUID sourceRoundId) {
		return jdbc.sql("SELECT target_round_id FROM task_carryovers WHERE source_round_id = :r").param("r", sourceRoundId)
				.query(UUID.class).optional().orElse(null);
	}
}
