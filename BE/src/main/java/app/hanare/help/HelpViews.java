package app.hanare.help;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.help.HelpDtos.HelpRequestCard;
import app.hanare.help.HelpDtos.HelpRequestDetail;
import app.hanare.help.HelpDtos.PointImpact;
import app.hanare.help.HelpDtos.SupportOfferDto;
import app.hanare.help.HelpDtos.SupportSubmissionDto;
import app.hanare.help.HelpDtos.SupportTimelineEvent;
import app.hanare.help.HelpDtos.SupportWorkDto;
import app.hanare.help.HelpDtos.SupportWorkSummary;
import app.hanare.points.EvidenceViews;
import app.hanare.points.Lookups;
import app.hanare.points.PointAmount;
import app.hanare.points.PointDtos.ContributionDto;
import app.hanare.points.PointMath;
import app.hanare.points.Refs.ReasonRef;
import app.hanare.points.Refs.UserRef;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.TaskFacts;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Nạp và dựng DTO cho yêu cầu hỗ trợ công khai / offer / SupportWork (đọc SQL). */
@Component
public class HelpViews {

	public record Req(UUID id, UUID projectId, UUID supportRequestId, UUID taskId, UUID roundId, UUID beneficiaryId,
			UUID approverId, UUID createdBy, String title, String description, String expectedOutput, String difficulty,
			Instant deadline, Integer effort, String skillNote, UUID budgetId, Long units, String status, Instant cancelledAt,
			Instant createdAt, long version) {
	}

	public record Work(UUID id, UUID projectId, UUID helpRequestId, UUID offerId, UUID helperId, UUID approverId,
			String scope, Instant deadline, String difficulty, Long units, String status, Instant createdAt,
			Instant updatedAt, long version, UUID successorId, String unableReason) {
	}

	public record Offer(UUID id, UUID projectId, UUID helpRequestId, UUID helperId, String message, String status,
			boolean needsReconfirmation, Instant createdAt, Instant decidedAt, UUID decidedBy) {
	}

	static final String REQ_SELECT = """
			SELECT id, project_id, support_request_id, task_id, round_id, beneficiary_id, result_approver_id, created_by,
			       title, description, expected_output, difficulty, deadline, effort_estimate_minutes, skill_note,
			       task_point_budget_id, advertised_point_units, status, cancelled_at, created_at, version
			  FROM help_requests
			""";

	static final String REQ_SELECT_H = """
			SELECT h.id, h.project_id, h.support_request_id, h.task_id, h.round_id, h.beneficiary_id, h.result_approver_id,
			       h.created_by, h.title, h.description, h.expected_output, h.difficulty, h.deadline,
			       h.effort_estimate_minutes, h.skill_note, h.task_point_budget_id, h.advertised_point_units, h.status,
			       h.cancelled_at, h.created_at, h.version
			  FROM help_requests h
			""";

	static final String WORK_SELECT = """
			SELECT id, project_id, help_request_id, accepted_offer_id, helper_id, result_approver_id, scope_snapshot,
			       deadline_snapshot, difficulty_snapshot, point_units_snapshot, status, created_at, updated_at, version,
			       successor_help_request_id, unable_reason
			  FROM support_works
			""";

	static final String OFFER_SELECT = """
			SELECT id, project_id, help_request_id, helper_id, message, status, needs_reconfirmation, created_at, decided_at,
			       decided_by
			  FROM support_offers
			""";

	private final JdbcClient jdbc;
	private final Clock clock;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final EvidenceViews evidence;

	public HelpViews(JdbcClient jdbc, Clock clock, Lookups lookups, TaskReader tasks, EvidenceViews evidence) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.lookups = lookups;
		this.tasks = tasks;
		this.evidence = evidence;
	}

	// ------------------------------------------------------------------------------------------------ loaders

	public static Req mapReq(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Req(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("support_request_id", UUID.class), rs.getObject("task_id", UUID.class),
				rs.getObject("round_id", UUID.class), rs.getObject("beneficiary_id", UUID.class),
				rs.getObject("result_approver_id", UUID.class), rs.getObject("created_by", UUID.class),
				rs.getString("title"), rs.getString("description"), rs.getString("expected_output"),
				rs.getString("difficulty"), DbTime.instant(rs, "deadline"), rs.getObject("effort_estimate_minutes", Integer.class),
				rs.getString("skill_note"), rs.getObject("task_point_budget_id", UUID.class),
				rs.getObject("advertised_point_units", Long.class), rs.getString("status"),
				DbTime.instant(rs, "cancelled_at"), DbTime.instant(rs, "created_at"), rs.getLong("version"));
	}

	public static Work mapWork(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Work(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("help_request_id", UUID.class), rs.getObject("accepted_offer_id", UUID.class),
				rs.getObject("helper_id", UUID.class), rs.getObject("result_approver_id", UUID.class),
				rs.getString("scope_snapshot"), DbTime.instant(rs, "deadline_snapshot"), rs.getString("difficulty_snapshot"),
				rs.getObject("point_units_snapshot", Long.class), rs.getString("status"), DbTime.instant(rs, "created_at"),
				DbTime.instant(rs, "updated_at"), rs.getLong("version"),
				rs.getObject("successor_help_request_id", UUID.class), rs.getString("unable_reason"));
	}

	public static Offer mapOffer(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Offer(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
				rs.getObject("help_request_id", UUID.class), rs.getObject("helper_id", UUID.class), rs.getString("message"),
				rs.getString("status"), rs.getBoolean("needs_reconfirmation"), DbTime.instant(rs, "created_at"),
				DbTime.instant(rs, "decided_at"), rs.getObject("decided_by", UUID.class));
	}

	public Req req(UUID id) {
		return jdbc.sql(REQ_SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> mapReq(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ."));
	}

	public Req reqForUpdate(UUID id) {
		return jdbc.sql(REQ_SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query((rs, i) -> mapReq(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy yêu cầu hỗ trợ."));
	}

	public Optional<Work> workOfRequest(UUID helpRequestId) {
		return jdbc.sql(WORK_SELECT + " WHERE help_request_id = :h").param("h", helpRequestId)
				.query((rs, i) -> mapWork(rs)).optional();
	}

	public Work work(UUID id) {
		return jdbc.sql(WORK_SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> mapWork(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy phần việc hỗ trợ."));
	}

	public Work workForUpdate(UUID id) {
		return jdbc.sql(WORK_SELECT + " WHERE id = :id FOR UPDATE").param("id", id).query((rs, i) -> mapWork(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy phần việc hỗ trợ."));
	}

	public Offer offer(UUID id) {
		return jdbc.sql(OFFER_SELECT + " WHERE id = :id").param("id", id).query((rs, i) -> mapOffer(rs)).optional()
				.orElseThrow(() -> ApiException.notFound("Không tìm thấy đề nghị hỗ trợ."));
	}

	public List<Offer> offersOf(UUID helpRequestId) {
		return jdbc.sql(OFFER_SELECT + " WHERE help_request_id = :h ORDER BY created_at, id").param("h", helpRequestId)
				.query((rs, i) -> mapOffer(rs)).list();
	}

	// ------------------------------------------------------------------------------------------------ nhãn

	public static String requestStatusLabel(String status) {
		return switch (status) {
			case "OPEN" -> "Đang tìm người nhận làm thay toàn bộ task";
			case "MATCHED" -> "Đã có người được chọn";
			case "IN_PROGRESS" -> "Đang được thực hiện";
			case "SUBMITTED" -> "Kết quả đang chờ xác nhận";
			case "COMPLETED" -> "Đã hoàn tất";
			default -> "Đã đóng";
		};
	}

	public static String workStatusLabel(String status) {
		return switch (status) {
			case "IN_PROGRESS" -> "Đang thực hiện";
			case "SUBMITTED" -> "Đã gửi kết quả";
			case "REVISION_REQUESTED" -> "Cần bổ sung";
			case "NEEDS_DISCUSSION" -> "Cần thảo luận";
			default -> "Đã xác nhận";
		};
	}

	public static String pointLabel(String difficulty, Long units) {
		return units == null ? null
				: PointMath.difficultyLabel(difficulty) + " · +" + PointMath.label(units) + " điểm % khi TL duyệt";
	}

	private static String preview(String text) {
		String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
		return t.length() <= 200 ? t : t.substring(0, 197) + "...";
	}

	// ------------------------------------------------------------------------------------------------ offer / request

	public SupportOfferDto offerDto(Offer o) {
		return new SupportOfferDto(o.id(), o.helpRequestId(), lookups.user(o.helperId()), o.message(), o.status(),
				o.needsReconfirmation(), o.createdAt(), o.decidedAt(), o.decidedBy() == null ? null : lookups.user(o.decidedBy()));
	}

	private boolean canSeeWork(Req r, Work w, UUID viewer) {
		return w != null && (viewer.equals(w.helperId()) || viewer.equals(r.createdBy()) || viewer.equals(r.beneficiaryId())
				|| viewer.equals(w.approverId()));
	}

	private String role(Req r, Work w, UUID viewer) {
		if (viewer.equals(r.createdBy())) {
			return "REQUESTER";
		}
		if (viewer.equals(r.beneficiaryId())) {
			return "BENEFICIARY";
		}
		if (w != null && viewer.equals(w.helperId())) {
			return "HELPER";
		}
		return "VIEWER";
	}

	public HelpRequestCard card(Req r, UUID viewer) {
		Work w = workOfRequest(r.id()).orElse(null);
		Offer mine = myOffer(r.id(), viewer);
		return new HelpRequestCard(r.id(), r.projectId(), r.title(), preview(r.description()), r.expectedOutput(),
				lookups.taskRef(r.taskId()), lookups.user(r.createdBy()), lookups.user(r.beneficiaryId()), r.deadline(),
				r.difficulty(), r.effort(), PointAmount.ofOrNull(r.units()), pointLabel(r.difficulty(), r.units()), r.status(),
				requestStatusLabel(r.status()), role(r, w, viewer), mine == null ? null : mine.status(),
				canSeeWork(r, w, viewer) ? w.id() : null, r.createdAt());
	}

	/** Offer đáng chú ý của người xem: đang hoạt động (PENDING/ACCEPTED), nếu không thì cái mới nhất. */
	public Offer myOffer(UUID helpRequestId, UUID viewer) {
		List<Offer> mine = jdbc.sql(OFFER_SELECT + " WHERE help_request_id = :h AND helper_id = :u ORDER BY created_at DESC, id")
				.param("h", helpRequestId).param("u", viewer).query((rs, i) -> mapOffer(rs)).list();
		for (Offer o : mine) {
			if ("PENDING".equals(o.status()) || "ACCEPTED".equals(o.status())) {
				return o;
			}
		}
		return mine.isEmpty() ? null : mine.get(0);
	}

	public HelpRequestDetail detail(Req r, UUID viewer, boolean viewerIsLead) {
		HelpRequestCard c = card(r, viewer);
		Work w = workOfRequest(r.id()).orElse(null);
		Offer mine = myOffer(r.id(), viewer);
		boolean requester = viewer.equals(r.createdBy());
		List<Offer> all = requester ? offersOf(r.id()) : List.of();
		List<SupportOfferDto> offers = all.stream().map(this::offerDto).toList();
		PointImpact impact = null;
		boolean excluded = viewer.equals(r.createdBy()) || viewer.equals(r.beneficiaryId()) || viewer.equals(r.approverId());
		if (r.units() != null && "OPEN".equals(r.status()) && !excluded) {
			long before = jdbc.sql("""
					SELECT COALESCE(SUM(b.point_units), 0) FROM task_point_budgets b
					  JOIN task_point_assignments a ON a.budget_id = b.id AND a.status = 'CURRENT'
					 WHERE b.project_id = :p AND b.state = 'FUNDED' AND a.recipient_id = :u
					""").param("p", r.projectId()).param("u", viewer).query(Long.class).single();
			impact = new PointImpact(PointAmount.of(before), PointAmount.of(before + r.units()), PointAmount.delta(r.units()));
		}
		List<String> actions = new ArrayList<>();
		if ("OPEN".equals(r.status())) {
			boolean active = mine != null && ("PENDING".equals(mine.status()) || "ACCEPTED".equals(mine.status()));
			if (!excluded && !active) {
				actions.add("OFFER_HELP");
			}
			if (mine != null && "PENDING".equals(mine.status())) {
				actions.add("WITHDRAW_OFFER");
				if (mine.needsReconfirmation()) {
					actions.add("RECONFIRM_OFFER");
				}
			}
			if (requester) {
				if (all.stream().anyMatch(o -> "PENDING".equals(o.status()))) {
					actions.add("ACCEPT_OFFER");
				}
				actions.add("EDIT");
				actions.add("CANCEL");
			}
		}
		if (canSeeWork(r, w, viewer)) {
			actions.add("OPEN_WORK");
		}
		return new HelpRequestDetail(c.id(), c.groupId(), c.title(), c.descriptionPreview(), c.expectedOutput(), c.task(),
				c.postedBy(), c.beneficiary(), c.deadline(), c.difficulty(), c.estimatedEffortMinutes(), c.points(),
				c.pointLabel(), c.status(), c.statusLabel(), c.myRoleInRequest(), c.myOfferStatus(), c.workId(), c.createdAt(),
				r.description(), r.skillNote(), viewerIsLead ? r.supportRequestId() : null, lookups.user(r.approverId()),
				mine == null ? null : offerDto(mine), offers, canSeeWork(r, w, viewer) ? workSummary(w, r) : null, impact,
				r.units() == null ? null : "Điểm được chuyển toàn bộ từ quỹ của task; chỉ ghi sau khi TL duyệt kết quả.",
				actions, r.version());
	}

	// ------------------------------------------------------------------------------------------------ work

	private boolean pointRecorded(Req r, Work w) {
		if (r.budgetId() == null) {
			return false;
		}
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM task_point_settlements s JOIN contributions c ON c.id = s.contribution_id
				                WHERE s.budget_id = :b AND s.revoked_at IS NULL AND c.work_id = :w AND c.status = 'CONFIRMED')
				""").param("b", r.budgetId()).param("w", w.id()).query(Boolean.class).single();
	}

	private String contributionStatus(Work w) {
		return jdbc.sql("SELECT status FROM contributions WHERE work_id = :w").param("w", w.id()).query(String.class)
				.optional().orElse(null);
	}

	public SupportWorkSummary workSummary(Work w, Req r) {
		return new SupportWorkSummary(w.id(), w.helpRequestId(), r.title(), lookups.user(w.helperId()),
				lookups.user(r.createdBy()), w.status(), r.status(), workStatusLabel(w.status()),
				PointAmount.ofOrNull(w.units()), pointRecorded(r, w), contributionStatus(w), w.updatedAt());
	}

	/** Điều kiện nộp kết quả của Helper: [ok, reason]. */
	public record SubmitCheck(boolean ok, ReasonRef reason) {
	}

	public SubmitCheck submitCheck(Work w, Req r) {
		if (!"IN_PROGRESS".equals(w.status()) && !"REVISION_REQUESTED".equals(w.status())) {
			String msg = switch (w.status()) {
				case "SUBMITTED" -> "Kết quả đã được gửi và đang chờ TL xác nhận.";
				case "APPROVED" -> "Phần việc đã được xác nhận.";
				default -> "Phần việc đang chờ thảo luận với TL.";
			};
			return new SubmitCheck(false, new ReasonRef("WORK_NOT_SUBMITTABLE", msg));
		}
		TaskFacts f = tasks.facts(r.projectId(), r.taskId()).orElse(null);
		if (f == null || !w.helperId().equals(f.assigneeId()) || !("ACTIVE".equals(f.roundState())
				|| "AWAITING_REVIEW".equals(f.roundState()))) {
			return new SubmitCheck(false, new ReasonRef("WORK_NOT_SUBMITTABLE",
					"Đang chờ TL chốt deadline xử lý tiếp; bạn nộp lại được sau khi vòng mới được mở."));
		}
		if (clock.instant().isAfter(f.cycleClosesAt())) {
			return new SubmitCheck(false, new ReasonRef("SUBMISSION_WINDOW_CLOSED", "Kỳ chứa vòng này đã đóng, không nộp thêm được."));
		}
		return new SubmitCheck(true, null);
	}

	public List<SupportSubmissionDto> submissions(Work w) {
		return jdbc.sql("""
				SELECT ss.id, ss.work_id, ss.revision_no, ss.result_note, ss.evidence::text ev, ss.actual_minutes,
				       ss.submitted_at, ss.task_submission_id, rv.decision, rv.issue_description
				  FROM support_submissions ss
				  LEFT JOIN task_result_reviews rv ON rv.submission_id = ss.task_submission_id
				 WHERE ss.work_id = :w ORDER BY ss.revision_no
				""").param("w", w.id())
				.query((rs, i) -> new SupportSubmissionDto(rs.getObject("id", UUID.class), rs.getObject("work_id", UUID.class),
						rs.getInt("revision_no"), rs.getString("result_note"), evidence.evidence(rs.getString("ev")),
						rs.getObject("actual_minutes", Integer.class), DbTime.instant(rs, "submitted_at"),
						rs.getObject("task_submission_id", UUID.class), rs.getString("decision"),
						rs.getString("issue_description")))
				.list();
	}

	public SupportWorkDto workDto(Work w, UUID viewer) {
		Req r = req(w.helpRequestId());
		TaskFacts f = tasks.facts(r.projectId(), r.taskId()).orElseThrow();
		SubmitCheck check = submitCheck(w, r);
		List<SupportSubmissionDto> subs = submissions(w);
		UUID latestTaskSub = subs.isEmpty() ? null : subs.get(subs.size() - 1).taskSubmissionId();
		boolean pending = tasks.latestSubmission(f.roundId()).map(s -> s.decision() == null).orElse(false);
		boolean canReview = clock.instant().isAfter(f.cycleClosesAt()) && pending && "SUBMITTED".equals(w.status());
		List<SupportTimelineEvent> timeline = jdbc.sql("""
				SELECT occurred_at, kind, actor_id, note FROM support_work_events WHERE work_id = :w ORDER BY occurred_at, seq
				""").param("w", w.id())
				.query((rs, i) -> new SupportTimelineEvent(DbTime.instant(rs, "occurred_at"), rs.getString("kind"),
						rs.getObject("actor_id", UUID.class) == null ? null : lookups.user(rs.getObject("actor_id", UUID.class)),
						rs.getString("note")))
				.list();
		Optional<UUID> contributionId = jdbc.sql("SELECT id FROM contributions WHERE work_id = :w").param("w", w.id())
				.query(UUID.class).optional();
		ContributionDto contribution = contributionId.flatMap(evidence::contribution).orElse(null);
		boolean recorded = pointRecorded(r, w);
		String pointLabel = w.units() == null ? null
				: recorded ? "Đã ghi nhận +" + PointMath.label(w.units()) + " điểm %"
						: "+" + PointMath.label(w.units()) + " điểm % khi TL duyệt (chưa ghi điểm)";
		UUID roundId = w.helperId().equals(f.assigneeId()) && ("ACTIVE".equals(f.roundState())
				|| "AWAITING_REVIEW".equals(f.roundState())) ? f.roundId() : null;
		List<String> actions = new ArrayList<>();
		if (viewer.equals(w.helperId())) {
			if (check.ok()) {
				actions.add("SUBMIT_RESULT");
			}
			if ("IN_PROGRESS".equals(w.status()) || "REVISION_REQUESTED".equals(w.status())) {
				actions.add("REPORT_UNABLE");
			}
		}
		if (viewer.equals(w.approverId()) && canReview) {
			actions.add("REVIEW_RESULT");
		}
		if (viewer.equals(r.createdBy()) && "NEEDS_DISCUSSION".equals(w.status()) && "IN_PROGRESS".equals(r.status())) {
			actions.add("RESOLVE_DISCUSSION");
		}
		if ("APPROVED".equals(w.status())) {
			actions.add("VIEW_CONTRIBUTION");
		}
		UserRef requester = lookups.user(r.createdBy());
		return new SupportWorkDto(w.id(), w.helpRequestId(), r.title(), lookups.user(w.helperId()), requester, w.status(),
				r.status(), workStatusLabel(w.status()), PointAmount.ofOrNull(w.units()), recorded,
				contribution == null ? null : contribution.status(), w.updatedAt(), w.projectId(), lookups.taskRef(r.taskId()),
				lookups.user(r.beneficiaryId()), lookups.user(w.approverId()), w.scope(), r.expectedOutput(), w.deadline(),
				w.difficulty(), pointLabel, roundId, subs, timeline, contribution, latestTaskSub, f.cycleClosesAt(),
				check.ok(), check.reason(), canReview, w.successorId(), actions, w.version());
	}
}
