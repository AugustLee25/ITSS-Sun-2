package app.hanare.task;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The only task-submission writer, shared by S05, checkpoint and Helper execution. */
@Component
public class SubmissionWriter {
	private final JdbcClient jdbc;
	private final TaskReader reader;
	private final Scope.Factory scopes;
	private final GroupAccess access;
	private final Ops ops;
	private final TaskViews views;
	public SubmissionWriter(JdbcClient jdbc, TaskReader reader, Scope.Factory scopes, GroupAccess access, Ops ops, TaskViews views) {
		this.jdbc = jdbc; this.reader = reader; this.scopes = scopes; this.access = access; this.ops = ops; this.views = views;
	}
	public TaskReader.Sub write(UUID roundId, CurrentUser user, ResultService.SubmitResultRequest request, boolean fromReport) {
		TaskReader.Ctx initial = reader.require(roundId);
		access.requireMember(initial.round().projectId(), user);
		WorkflowLocks.round(jdbc, roundId);
		TaskReader.Ctx c = reader.require(roundId);
		Scope s = scopes.of(c.round().projectId());
		if (!user.id().equals(c.executor())) throw ApiException.of(ErrorCode.NOT_ASSIGNEE, null);
		if (!roundId.equals(reader.currentRoundOf(c.round().taskId()).orElse(null))) throw ApiException.invalidState("Vòng cũ chỉ được xem.");
		if (c.effective() == null) throw ApiException.of(HttpStatus.CONFLICT, "NO_EFFECTIVE_PLAN", "Task chưa có kế hoạch được duyệt.");
		if (!List.of("ACTIVE", "AWAITING_REVIEW").contains(c.round().state()))
			throw ApiException.of(HttpStatus.CONFLICT, "ROUND_NOT_ACTIVE", "Vòng không còn nhận kết quả.");
		if (!c.effective().id().equals(request.planRevisionId())) throw ApiException.of(HttpStatus.CONFLICT, "PLAN_CHANGED", "Kế hoạch đã đổi, hãy tải lại.");
		if (s.cycle(c.round().cycleId()).isClosed(ops.now()))
			throw ApiException.of(HttpStatus.CONFLICT, "SUBMISSION_WINDOW_CLOSED", "Đã quá giờ chốt kỳ.");
		if (c.open() != null) throw ApiException.of(HttpStatus.CONFLICT, "REVISION_ALREADY_IN_FLIGHT", "Hãy xử lý hoặc hủy đề xuất kế hoạch trước khi nộp.");
		if (!fromReport && TaskWritePolicy.reported(jdbc, roundId)) throw ApiException.invalidState("Hãy cập nhật kết quả qua báo cáo checkpoint.");
		List<ResultService.EvidenceInput> evidence = ResultService.validateEvidence(request);
		if (request.resultNote() != null && request.resultNote().length() > 2000) throw ApiException.validation("resultNote", "Tối đa 2000 ký tự.");
		Instant now = ops.now();
		if(s.cycle(c.round().cycleId()).isClosed(now))
			throw ApiException.of(HttpStatus.CONFLICT,"SUBMISSION_WINDOW_CLOSED","Đã quá giờ chốt kỳ.");
		UUID id = jdbc.sql("""
				INSERT INTO task_submissions(project_id,round_id,plan_revision_id,submission_no,submitted_by,result_note,evidence,submitted_at,created_at)
				VALUES(:p,:r,:plan,(SELECT COALESCE(MAX(submission_no),0)+1 FROM task_submissions WHERE round_id=:r),:u,:n,CAST(:ev AS jsonb),:at,:at) RETURNING id
				""").param("p", c.round().projectId()).param("r", roundId).param("plan", c.effective().id()).param("u", user.id())
				.param("n", request.resultNote() == null ? null : request.resultNote().strip())
				.param("ev", views.evidenceJson(evidence.stream().map(e -> J.o("type", "URL", "url", e.url().strip(), "label", e.label())).toList()))
				.param("at", DbTime.toDb(now)).query(UUID.class).single();
		jdbc.sql("UPDATE task_review_rounds SET state='AWAITING_REVIEW', work_started_at=COALESCE(work_started_at,:at), version=version+1 WHERE id=:r")
				.param("r", roundId).param("at", DbTime.toDb(now)).update();
		ops.bumpTask(c.round().taskId());
		TaskReader.Sub sub = reader.submission(id).orElseThrow();
		ops.history(c.round().projectId(), user.id(), c.round().taskId(), "RESULT_SUBMITTED", c.round().roundNo(),
				s.name(user.id()) + " nộp kết quả lần " + sub.no(), id);
		return sub;
	}
}
