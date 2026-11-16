package app.hanare.help;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.help.HelpDtos.AcceptOfferRequest;
import app.hanare.help.HelpDtos.AcceptOfferResponse;
import app.hanare.help.HelpDtos.CreateOfferRequest;
import app.hanare.help.HelpDtos.ReconfirmOfferRequest;
import app.hanare.help.HelpDtos.SupportOfferDto;
import app.hanare.help.HelpViews.Offer;
import app.hanare.help.HelpViews.Req;
import app.hanare.help.HelpViews.Work;
import app.hanare.notification.NotificationEvent;
import app.hanare.notification.NotificationService;
import app.hanare.points.AuditWriter;
import app.hanare.points.Err;
import app.hanare.points.IdempotencyKey;
import app.hanare.points.Lookups;
import app.hanare.points.PointMath;
import app.hanare.points.TaskReader;
import app.hanare.points.TaskReader.BudgetFacts;
import app.hanare.points.TaskReader.TaskFacts;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offer tự nguyện (không có "từ chối/bỏ qua"), rút, xác nhận lại, và TL chấp thuận: một transaction bàn giao người thực
 * hiện + toàn bộ quyền nhận P_t (không ghi ledger — điểm chỉ ghi khi TL duyệt kết quả).
 */
@Service
public class OfferService {

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Lookups lookups;
	private final TaskReader tasks;
	private final HelpViews views;
	private final HandoffService handoff;
	private final SupportWorkService works;
	private final AuditWriter audit;
	private final NotificationService notifications;
	private final Clock clock;

	public OfferService(JdbcClient jdbc, GroupAccess access, Lookups lookups, TaskReader tasks, HelpViews views,
			HandoffService handoff, SupportWorkService works, AuditWriter audit, NotificationService notifications,
			Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.lookups = lookups;
		this.tasks = tasks;
		this.views = views;
		this.handoff = handoff;
		this.works = works;
		this.audit = audit;
		this.notifications = notifications;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------------ gửi / rút / xác nhận lại

	@Transactional
	public SupportOfferDto create(UUID helpRequestId, CurrentUser user, CreateOfferRequest req) {
		Req r0 = views.req(helpRequestId);
		access.requireMember(r0.projectId(), user);
		if (!Boolean.TRUE.equals(req.acknowledgedScope())) {
			throw ApiException.validation("acknowledgedScope", "Cần xác nhận đã xem phạm vi, deadline và điểm.");
		}
		Req r = views.reqForUpdate(helpRequestId);
		if (user.id().equals(r.createdBy()) || user.id().equals(r.beneficiaryId()) || user.id().equals(r.approverId())) {
			throw ApiException.of(ErrorCode.SELF_ACTION_FORBIDDEN,
					"Người đăng, người được hỗ trợ và người duyệt không đề nghị hỗ trợ phần việc của chính mình.");
		}
		if (!"OPEN".equals(r.status())) {
			if ("CANCELLED".equals(r.status())) {
				throw Err.conflict("HELP_REQUEST_NOT_OPEN", "Yêu cầu không còn mở.");
			}
			throw Err.conflict("HELP_ALREADY_MATCHED", "Công việc đã có người hỗ trợ.");
		}
		if (r.version() != req.helpRequestVersion()) {
			throw ApiException.versionConflict();
		}
		Optional<Offer> active = views.offersOf(helpRequestId).stream()
				.filter(o -> o.helperId().equals(user.id()) && ("PENDING".equals(o.status()) || "ACCEPTED".equals(o.status())))
				.findFirst();
		if (active.isPresent()) {
			throw Err.conflict("OFFER_ALREADY_EXISTS", "Bạn đã gửi đề nghị cho phần việc này.");
		}
		Instant now = clock.instant();
		UUID id = jdbc.sql("""
				INSERT INTO support_offers (project_id, help_request_id, helper_id, message, created_at)
				VALUES (:p, :h, :u, :m, :at) RETURNING id
				""").param("p", r.projectId()).param("h", helpRequestId).param("u", user.id())
				.param("m", req.message() == null || req.message().isBlank() ? null : req.message().trim())
				.param("at", DbTime.toDb(now)).query(UUID.class).single();
		notifyOffered(r, user.id(), id, "offer:" + id);
		return views.offerDto(views.offer(id));
	}

	private void notifyOffered(Req r, UUID helper, UUID offerId, String dedupSuffix) {
		notifications.publish(new NotificationEvent(r.projectId(), "SUPPORT_OFFERED", "HELP_REQUEST", r.id(), null,
				"help:" + r.id() + ":" + dedupSuffix, List.of(r.createdBy()), "Có người sẵn lòng hỗ trợ",
				lookups.userName(helper) + " đề nghị hỗ trợ \"" + r.title() + "\".",
				Map.of("helpRequestId", r.id().toString(), "offerId", offerId.toString())));
	}

	@Transactional
	public SupportOfferDto withdraw(UUID offerId, CurrentUser user) {
		Offer o0 = views.offer(offerId);
		access.requireMember(o0.projectId(), user);
		if (!user.id().equals(o0.helperId())) {
			throw ApiException.forbidden("Chỉ người gửi đề nghị mới rút được.");
		}
		views.reqForUpdate(o0.helpRequestId());
		Offer o = views.offer(offerId);
		if ("WITHDRAWN".equals(o.status())) {
			return views.offerDto(o);
		}
		if (!"PENDING".equals(o.status())) {
			throw Err.conflict("OFFER_NOT_PENDING", "Đề nghị không còn ở trạng thái chờ.");
		}
		jdbc.sql("UPDATE support_offers SET status = 'WITHDRAWN', needs_reconfirmation = false WHERE id = :id")
				.param("id", offerId).update();
		return views.offerDto(views.offer(offerId));
	}

	@Transactional
	public SupportOfferDto reconfirm(UUID offerId, CurrentUser user, ReconfirmOfferRequest req) {
		Offer o0 = views.offer(offerId);
		access.requireMember(o0.projectId(), user);
		if (!user.id().equals(o0.helperId())) {
			throw ApiException.forbidden("Chỉ người gửi đề nghị mới xác nhận lại được.");
		}
		Req r = views.reqForUpdate(o0.helpRequestId());
		Offer o = views.offer(offerId);
		if (!"PENDING".equals(o.status())) {
			throw Err.conflict("OFFER_NOT_PENDING", "Đề nghị không còn ở trạng thái chờ.");
		}
		if (!"OPEN".equals(r.status())) {
			throw Err.conflict("HELP_REQUEST_NOT_OPEN", "Yêu cầu không còn mở.");
		}
		if (r.version() != req.helpRequestVersion()) {
			throw ApiException.versionConflict();
		}
		jdbc.sql("UPDATE support_offers SET needs_reconfirmation = false WHERE id = :id").param("id", offerId).update();
		notifyOffered(r, user.id(), offerId, "offer:" + offerId + ":reconfirm:" + r.version());
		return views.offerDto(views.offer(offerId));
	}

	// ------------------------------------------------------------------------------------------------ TL chấp thuận

	@Transactional
	public AcceptOfferResponse accept(UUID helpRequestId, CurrentUser user, String idempotencyKey, AcceptOfferRequest req) {
		Req r0 = views.req(helpRequestId);
		GroupMembership lead = access.requireLeader(r0.projectId(), user);
		IdempotencyKey.require(idempotencyKey);
		jdbc.sql("SELECT id FROM tasks WHERE id = :t FOR UPDATE").param("t", r0.taskId()).query(UUID.class).single();
		Req r = views.reqForUpdate(helpRequestId);
		if (!lead.userId().equals(r.createdBy())) {
			throw ApiException.of(ErrorCode.LEAD_ONLY, "Chỉ người đăng yêu cầu được chấp thuận đề nghị.");
		}
		Offer offer = views.offer(req.offerId());
		if (!offer.helpRequestId().equals(helpRequestId)) {
			throw ApiException.notFound("Đề nghị không thuộc yêu cầu này.");
		}
		if (!"OPEN".equals(r.status())) {
			if ("CANCELLED".equals(r.status())) {
				throw Err.conflict("HELP_REQUEST_NOT_OPEN", "Yêu cầu không còn mở.");
			}
			if ("ACCEPTED".equals(offer.status())) {
				// idempotent tự nhiên: chấp thuận lặp lại cùng đề nghị trả kết quả hiện có
				return existing(r, lead.userId());
			}
			throw Err.conflict("HELP_ALREADY_MATCHED", "Công việc đã có người hỗ trợ.");
		}
		if (r.version() != req.version()) {
			throw ApiException.versionConflict();
		}
		if (!"PENDING".equals(offer.status())) {
			throw Err.conflict("OFFER_NOT_PENDING", "Đề nghị không còn ở trạng thái chờ.");
		}
		if (offer.needsReconfirmation()) {
			throw Err.conflict("OFFER_NEEDS_RECONFIRMATION", "Phạm vi yêu cầu đã đổi; Helper cần xác nhận lại đề nghị.");
		}
		TaskFacts f = tasks.facts(r.projectId(), r.taskId()).orElseThrow(() -> ApiException.notFound("Không tìm thấy task."));
		handoff.requireTransferable(f);
		UUID beneficiary = r.beneficiaryId();
		if (!beneficiary.equals(tasks.executorOf(f))) {
			throw Err.conflict("TASK_NOT_TRANSFERABLE", "Người thực hiện task đã thay đổi kể từ khi đăng yêu cầu.");
		}
		Optional<BudgetFacts> budget = r.budgetId() == null ? Optional.empty() : tasks.budget(r.taskId());
		if (budget.isPresent()) {
			if (budget.get().awarded()) {
				throw Err.conflict("TASK_ALREADY_DONE", "Task đã được ghi điểm.");
			}
			if (!budget.get().recipientId().equals(beneficiary)) {
				throw Err.conflict("TASK_NOT_TRANSFERABLE", "Quyền nhận điểm của task đã thay đổi.");
			}
		}
		Instant now = clock.instant();
		UUID helper = offer.helperId();
		// offer được chọn / các offer còn lại không được chọn (lịch sự, không lý do)
		List<UUID> notSelected = jdbc.sql("""
				SELECT id FROM support_offers WHERE help_request_id = :h AND status = 'PENDING' AND id <> :o ORDER BY created_at
				""").param("h", helpRequestId).param("o", offer.id()).query(UUID.class).list();
		List<UUID> notSelectedHelpers = jdbc.sql("""
				SELECT helper_id FROM support_offers WHERE help_request_id = :h AND status = 'PENDING' AND id <> :o ORDER BY created_at
				""").param("h", helpRequestId).param("o", offer.id()).query(UUID.class).list();
		jdbc.sql("UPDATE support_offers SET status = 'ACCEPTED', decided_at = :at, decided_by = :by WHERE id = :id")
				.param("at", DbTime.toDb(now)).param("by", lead.userId()).param("id", offer.id()).update();
		jdbc.sql("""
				UPDATE support_offers SET status = 'NOT_SELECTED', decided_at = :at, decided_by = :by, needs_reconfirmation = false
				 WHERE help_request_id = :h AND status = 'PENDING'
				""").param("at", DbTime.toDb(now)).param("by", lead.userId()).param("h", helpRequestId).update();
		UUID workId = jdbc.sql("""
				INSERT INTO support_works (project_id, help_request_id, accepted_offer_id, helper_id, result_approver_id,
				    scope_snapshot, deadline_snapshot, difficulty_snapshot, point_units_snapshot, created_at, updated_at)
				VALUES (:p, :h, :o, :u, :apr, :scope, :dl, :diff, :units, :at, :at) RETURNING id
				""").param("p", r.projectId()).param("h", helpRequestId).param("o", offer.id()).param("u", helper)
				.param("apr", r.approverId()).param("scope", r.description()).param("dl", DbTime.toDb(r.deadline()))
				.param("diff", r.difficulty()).param("units", r.units()).param("at", DbTime.toDb(now)).query(UUID.class)
				.single();
		jdbc.sql("UPDATE help_requests SET status = 'IN_PROGRESS', updated_at = :at, version = version + 1 WHERE id = :id")
				.param("at", DbTime.toDb(now)).param("id", helpRequestId).update();
		if (budget.isPresent()) {
			jdbc.sql("UPDATE task_point_reservations SET state = 'CONSUMED', updated_at = :at WHERE help_request_id = :h AND state = 'HELD'")
					.param("at", DbTime.toDb(now)).param("h", helpRequestId).update();
			handoff.swapAssignment(r.projectId(), budget.get(), helper, helpRequestId, workId, lead.userId(), now);
		}
		handoff.handoffRevision(f, helper, lead.userId(), now, "Bàn giao hỗ trợ cho " + lookups.userName(helper));
		jdbc.sql("""
				INSERT INTO support_work_events (project_id, work_id, kind, actor_id, occurred_at)
				VALUES (:p, :w, 'OFFER_ACCEPTED', :a, :at)
				""").param("p", r.projectId()).param("w", workId).param("a", lead.userId()).param("at", DbTime.toDb(now))
				.update();
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("helperId", helper);
		after.put("workId", workId);
		after.put("units", r.units());
		audit.log(r.projectId(), lead.userId(), "HELP_REQUEST", helpRequestId, "HELP_OFFER_ACCEPTED", null, after);
		notifications.publish(new NotificationEvent(r.projectId(), "SUPPORT_ACCEPTED", "SUPPORT_WORK", workId, null,
				"help:" + helpRequestId + ":accepted:" + offer.id(), List.of(helper), "Đề nghị hỗ trợ đã được chấp thuận",
				"TL đã chấp thuận đề nghị của bạn cho \"" + r.title() + "\". Điểm chỉ được ghi sau khi TL duyệt kết quả.",
				Map.of("workId", workId.toString(), "taskId", r.taskId().toString())));
		if (budget.isPresent()) {
			notifications.publish(new NotificationEvent(r.projectId(), "POINT_ASSIGNMENT_CHANGED", "HELP_REQUEST", helpRequestId,
					null, "help:" + helpRequestId + ":assignment", List.of(beneficiary, helper), "Quyền nhận điểm đã chuyển",
					"Quyền nhận " + PointMath.label(r.units()) + " điểm % của task " + f.title() + " đã chuyển cho "
							+ lookups.userName(helper) + " (khả năng nhận, chưa phải điểm đã ghi).",
					Map.of("taskId", r.taskId().toString(), "helpRequestId", helpRequestId.toString())));
		}
		if (!notSelectedHelpers.isEmpty()) {
			notifications.publish(new NotificationEvent(r.projectId(), "SUPPORT_NOT_SELECTED", "HELP_REQUEST", helpRequestId,
					null, "help:" + helpRequestId + ":not-selected", notSelectedHelpers, "Cảm ơn bạn đã sẵn lòng hỗ trợ",
					"Yêu cầu \"" + r.title() + "\" đã có người phụ trách. Cảm ơn bạn đã sẵn lòng giúp nhóm.",
					Map.of("helpRequestId", helpRequestId.toString())));
		}
		return response(helpRequestId, workId, lead.userId(), notSelected);
	}

	private AcceptOfferResponse existing(Req r, UUID viewer) {
		Work w = views.workOfRequest(r.id()).orElseThrow();
		List<UUID> notSelected = views.offersOf(r.id()).stream().filter(o -> "NOT_SELECTED".equals(o.status())).map(Offer::id)
				.toList();
		return response(r.id(), w.id(), viewer, new ArrayList<>(notSelected));
	}

	private AcceptOfferResponse response(UUID helpRequestId, UUID workId, UUID viewer, List<UUID> notSelected) {
		Req fresh = views.req(helpRequestId);
		return new AcceptOfferResponse(views.detail(fresh, viewer, true), views.workDto(views.work(workId), viewer),
				tasks.summary(fresh.projectId(), fresh.taskId()), notSelected);
	}
}
