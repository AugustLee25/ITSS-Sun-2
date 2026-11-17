package app.hanare.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import app.hanare.schedule.CheckpointJob;

/** Regression coverage for the reviewed workflow: state guards, durable reports, batch atomicity and cutoff. */
class CheckpointWorkflowTest extends B1Base {
	@Autowired CheckpointJob job;
	@Autowired app.hanare.points.TaskReader pointsReader;
	private String report(World w) { return "/api/v1/groups/"+w.pid()+"/checkpoint-cycles/"+w.c1().id()+"/my-report"; }
	private String item(Task task, boolean complete) {
		return body("taskId",task.taskId(),"roundId",task.roundId(),"planRevisionId",effectivePlan(task),"submissionId",null,
				"declaredResult",complete?"RESULT_SUBMITTED":"RESULT_MISSING","riskLevel","ON_TRACK",
				"note","Cần bổ sung dữ liệu kiểm thử","expectedCompletionAt","2026-11-25T10:00:00Z",
				"evidence",complete?List.of(J.o("type","URL","url","https://example.test/result")):List.of());
	}
	private String support(UUID round) {
		return body("roundId",round,"obstacleType","TECHNICAL","description","Không có dữ liệu kiểm thử đầy đủ.","neededSupport","Cần TL hỗ trợ dữ liệu.");
	}
	@Test void executionAndPrivateSupportLockAfterResultReportAndCutoff() throws Exception {
		World w=world(); Task submitted=approvedTask(w,w.a(),"Đã gửi kết quả","EASY",DL);
		submitResult(submitted,w.a(),201);
		POST("/api/v1/tasks/"+submitted.taskId()+"/progress",w.a(),body("note","Ghi sau nộp"),409);
		POST("/api/v1/tasks/"+submitted.taskId()+"/support-requests",w.a(),support(submitted.roundId()),409);
		List<String> actions=at(GET("/api/v1/tasks/"+submitted.taskId(),w.a(),200),"$.allowedActions");
		assertThat(actions).doesNotContain("UPDATE_PROGRESS","REPORT_DIFFICULTY","PROPOSE_CHANGE");
		Task late=approvedTask(w,w.b(),"Job chưa kịp chạy","MEDIUM",DL);
		clock.set(w.c1().closesAt().plusMillis(1));
		POST("/api/v1/tasks/"+late.taskId()+"/start",w.b(),body("version",0),409);
		POST("/api/v1/tasks/"+late.taskId()+"/progress",w.b(),body("note","Ghi quá giờ"),409);
		MvcResult oldDashboard=GET("/api/v1/groups/"+w.pid()+"/dashboard?cycleId="+w.c1().id(),w.b(),200);
		List<String> historicalActions=at(oldDashboard,"$.myTasks[0].allowedActions");
		assertThat(historicalActions).doesNotContain("START","UPDATE_PROGRESS","REPORT_DIFFICULTY","SUBMIT_RESULT");
		assertThat(jdbc.sql("SELECT count(*) FROM task_progress_notes WHERE task_id=:t").param("t",late.taskId()).query(Long.class).single()).isZero();
	}
	@Test void memberDraftBatchIsAtomicAndCannotAssignSomeoneElse() throws Exception {
		World w=world(); String url="/api/v1/groups/"+w.pid()+"/tasks";
		POST(url,w.a(),body("title","Không tự giao người khác","cycleId",w.c1().id(),"assigneeId",w.b().id()),403);
		MvcResult first=createTask(w,w.a(),"Kế hoạch đầy đủ","EASY",DL);
		MvcResult second=POST(url,w.a(),body("title","Nháp chưa có đầu ra","cycleId",w.c1().id()),201);
		String batch="/api/v1/groups/"+w.pid()+"/plans/submit";
		POST(batch,w.a(),body("items",List.of(J.o("planId",uuidAt(first,"$.pendingRevision.id"),"version",0),J.o("planId",uuidAt(second,"$.pendingRevision.id"),"version",0))),422);
		assertThat((String)at(GET("/api/v1/tasks/"+uuidAt(first,"$.id"),w.a(),200),"$.planStatus")).isEqualTo("DRAFT");
		UUID secondPlan=uuidAt(second,"$.pendingRevision.id");
		MvcResult completed=PATCH("/api/v1/plans/"+secondPlan,w.a(),body("version",0,"description","Phạm vi đã thống nhất","expectedOutput","Bản chạy được",
				"startAt","2026-11-16T01:00:00Z","deadline",DL,"difficulty","HARD"),200);
		String batchBody=body("items",List.of(J.o("planId",uuidAt(first,"$.pendingRevision.id"),"version",0),J.o("planId",secondPlan,"version",longAt(completed,"$.plan.version"))));
		String key=UUID.randomUUID().toString();
		call(post(batch).header("Idempotency-Key",key).content(batchBody),w.a(),200);
		call(post(batch).header("Idempotency-Key",key).content(batchBody),w.a(),200);
		call(post(batch).header("Idempotency-Key",key).content(body("items",List.of(J.o("planId",secondPlan,"version",1)))),w.a(),409);
		assertThat((String)at(GET("/api/v1/tasks/"+uuidAt(first,"$.id"),w.a(),200),"$.planStatus")).isEqualTo("PENDING_APPROVAL");
	}
	@Test void taskAndPointsViewsShareExecutionAndRiskPolicy() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Trạng thái nhất quán","EASY",DL);
		assertThat(pointsReader.summary(w.pid(),task.taskId()).taskStatus()).isEqualTo("TODO");
		POST("/api/v1/tasks/"+task.taskId()+"/progress",w.a(),body("note","Cần dữ liệu thử nghiệm","riskStatus","BLOCKED"),201);
		MvcResult detail=GET("/api/v1/tasks/"+task.taskId(),w.a(),200);
		var points=pointsReader.summary(w.pid(),task.taskId());
		assertThat(points.taskStatus()).isEqualTo(at(detail,"$.taskStatus"));
		assertThat(points.riskStatus()).isEqualTo(at(detail,"$.riskStatus"));
		assertThat(points.timeliness()).isEqualTo(at(detail,"$.timeliness"));
	}
	@Test void cancellingAnOpenRevisionReleasesTheSubmissionWithoutChangingTheCommitment() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Hủy đề xuất đang mở","EASY",DL);
		POST("/api/v1/tasks/"+task.taskId()+"/plan-revisions",w.a(),body("changeReason","Đổi người không qua bàn giao","assigneeId",w.b().id()),400);
		MvcResult revision=POST("/api/v1/tasks/"+task.taskId()+"/plan-revisions",w.a(),body("changeReason","Bổ sung phạm vi kiểm thử","description","Phạm vi đề xuất mới"),201);
		submitResult(task,w.a(),409);
		POST("/api/v1/plans/"+uuidAt(revision,"$.plan.id")+"/cancel",w.a(),body("version",longAt(revision,"$.plan.version")),200);
		assertThat(effectivePlan(task)).isEqualTo(task.planId());
		submitResult(task,w.a(),201);
	}
	@Test void reportEvidenceAndAllRowsCommitTogetherAndReplay() throws Exception {
		World w=world(); Task a=approvedTask(w,w.a(),"Minh chứng tại checkpoint","EASY",DL);
		Task b=approvedTask(w,w.a(),"Cần xin thêm thời gian","HARD",DL);
		String bad="{\"version\":null,\"items\":["+item(a,true)+","+body("taskId",b.taskId(),"declaredResult","RESULT_MISSING","riskLevel","ON_TRACK")+"]}";
		POST(report(w)+"/submit",w.a(),bad,400);
		assertThat(jdbc.sql("SELECT count(*) FROM task_submissions WHERE round_id=:r").param("r",a.roundId()).query(Long.class).single()).isZero();
		long version=longAt(GET(report(w),w.a(),200),"$.version");
		String good="{\"version\":"+version+",\"items\":["+item(a,true)+","+item(b,false)+"]}";
		String key=UUID.randomUUID().toString();
		MvcResult sent=call(post(report(w)+"/submit").header("Idempotency-Key",key).content(good),w.a(),200);
		MvcResult replay=call(post(report(w)+"/submit").header("Idempotency-Key",key).content(good),w.a(),200);
		assertThat((String)at(replay,"$.reportId")).isEqualTo(at(sent,"$.reportId"));
		assertThat(jdbc.sql("SELECT count(*) FROM task_submissions WHERE round_id=:r").param("r",a.roundId()).query(Long.class).single()).isEqualTo(1);
		POST("/api/v1/tasks/"+b.taskId()+"/progress",w.a(),body("note","Ngoài báo cáo"),409);
		POST("/api/v1/tasks/"+b.taskId()+"/support-requests",w.a(),support(b.roundId()),409);
		call(post(report(w)+"/submit").header("Idempotency-Key",key).content(body("items",List.of(),"version",version)),w.a(),409);
	}
	@Test void oldReportWithoutPlanReferenceIsExplicitlyUnknown() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Báo cáo từ phiên bản cũ","EASY",DL);
		MvcResult draft=PUT(report(w),w.a(),"{\"version\":null,\"items\":["+item(task,false)+"]}",200);
		jdbc.sql("UPDATE checkpoint_report_items SET plan_revision_id=NULL WHERE round_id=:r").param("r",task.roundId()).update();
		POST(report(w)+"/submit",w.a(),body("version",longAt(draft,"$.version")),200);
		MvcResult old=GET(report(w)+"?revisionNo=1",w.a(),200);
		assertThat((Boolean)at(old,"$.items[0].planReferenceKnown")).isFalse();
		assertThat((Object)at(old,"$.items[0].planRevisionId")).isNull();
	}
	@Test void reviewAndCarryoverRollbackTogetherAndReplayTheSameDecision() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Duyệt và chuyển kỳ nguyên tử","EASY",DL);
		submitResult(task,w.a(),201);
		UUID sub=uuidAt(GET("/api/v1/tasks/"+task.taskId(),w.lead(),200),"$.submissions[0].id");
		clock.set(w.c1().closesAt().plusSeconds(1));
		String url="/api/v1/task-submissions/"+sub+"/review";
		POST(url,w.lead(),body("decision","FIX_REQUIRED","issueDescription","Cần bổ sung kiểm thử dữ liệu rỗng",
				"carryover",J.o("targetCycleId",w.c2().id(),"deadline","2026-12-25T10:00:00Z")),422);
		assertThat(jdbc.sql("SELECT count(*) FROM task_result_reviews WHERE submission_id=:s").param("s",sub).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM task_review_rounds WHERE task_id=:t").param("t",task.taskId()).query(Long.class).single()).isEqualTo(1);
		String body=body("decision","FIX_REQUIRED","issueDescription","Cần bổ sung kiểm thử dữ liệu rỗng",
				"carryover",J.o("targetCycleId",w.c2().id(),"deadline","2026-11-25T10:00:00Z"));
		String key=UUID.randomUUID().toString();
		call(post(url).header("Idempotency-Key",key).content(body),w.lead(),200);
		call(post(url).header("Idempotency-Key",key).content(body),w.lead(),200);
		call(post(url).header("Idempotency-Key",key).content(body.replace("FIX_REQUIRED","UPDATE_REQUIRED")),w.lead(),409);
		assertThat(jdbc.sql("SELECT count(*) FROM task_review_rounds WHERE task_id=:t").param("t",task.taskId()).query(Long.class).single()).isEqualTo(2);
	}
	@Test void submittedReportKeepsEvidenceAndCutoffIgnoresUnsentDraft() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Bảo toàn lịch sử minh chứng","EASY",DL);
		MvcResult first=POST(report(w)+"/submit",w.a(),"{\"version\":null,\"items\":["+item(task,true)+"]}",200);
		UUID a=uuidAt(first,"$.items[0].submission.id");
		String revised=body("taskId",task.taskId(),"roundId",task.roundId(),"planRevisionId",effectivePlan(task),"submissionId",a,
				"declaredResult","RESULT_SUBMITTED","riskLevel","ON_TRACK","evidence",List.of(J.o("type","URL","url","https://example.test/revision-b")));
		MvcResult second=POST(report(w)+"/submit",w.a(),"{\"version\":"+longAt(first,"$.version")+",\"items\":["+revised+"]}",200);
		UUID b=uuidAt(second,"$.items[0].submission.id"); assertThat(b).isNotEqualTo(a);
		MvcResult old=GET(report(w)+"?revisionNo=1",w.a(),200);
		assertThat(uuidAt(old,"$.items[0].submission.id")).isEqualTo(a);
		PUT(report(w),w.a(),body("version",longAt(second,"$.version"),"items",List.of(J.o("taskId",task.taskId(),"declaredResult","RESULT_SUBMITTED","riskLevel","ON_TRACK","note","Nháp chưa gửi"))),200);
		closeCycle(w,w.c1());
		MvcResult frozen=GET(report(w),w.a(),200);
		assertThat((Integer)at(frozen,"$.revisionNo")).isEqualTo(2);
		assertThat(uuidAt(frozen,"$.items[0].submission.id")).isEqualTo(b);
		assertThat(uuidAt(GET(report(w)+"?revisionNo=1",w.a(),200),"$.items[0].submission.id")).isEqualTo(a);
	}
	@Test void cutoffIsInclusiveAndReviewFreezesBeforeTheJobRuns() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Deadline trùng cutoff","EASY",w.c1().closesAt());
		clock.set(w.c1().closesAt());
		assertThat(job.runOnce(clock.instant()).closed()).isZero();
		MvcResult submitted=submitResult(task,w.a(),201);
		clock.set(w.c1().closesAt().plusMillis(1));
		POST("/api/v1/task-submissions/"+uuidAt(submitted,"$.submission.id")+"/review",w.lead(),body("decision","ACCEPTED"),200);
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_task_snapshots WHERE round_id=:r AND frozen_at IS NOT NULL")
				.param("r",task.roundId()).query(Long.class).single()).isEqualTo(1);
	}
	@Test void twoTabsCannotSendDifferentDraftsAsTheSameVersion() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Hai tab cùng gửi","EASY",DL);
		String json="{\"version\":null,\"items\":["+item(task,true)+"]}";
		ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
		try {
			Callable<Integer> send=()->{go.await(); return mvc.perform(as(post(report(w)+"/submit").content(json),w.a())).andReturn().getResponse().getStatus();};
			Future<Integer> a=pool.submit(send),b=pool.submit(send); go.countDown();
			assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
			assertThat(jdbc.sql("SELECT count(*) FROM task_submissions WHERE round_id=:r").param("r",task.roundId()).query(Long.class).single()).isEqualTo(1);
		} finally { pool.shutdownNow(); }
	}
	@Test void carryoverPreservesHistoryAndOldRoundCannotRequestSupport() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Chuyển kỳ giữ lịch sử","MEDIUM",DL);
		clock.set(w.c1().closesAt().plusSeconds(1));
		MvcResult moved=POST("/api/v1/task-rounds/"+task.roundId()+"/carryover",w.lead(),body("targetCycleId",w.c2().id(),"deadline","2026-11-26T10:00:00Z"),201);
		UUID target=uuidAt(moved,"$.targetRound.id");
		POST("/api/v1/tasks/"+task.taskId()+"/support-requests",w.a(),support(task.roundId()),400);
		MvcResult old=GET("/api/v1/groups/"+w.pid()+"/tasks?cycleId="+w.c1().id(),w.a(),200);
		assertThat(uuidAt(old,"$.items[0].roundId")).isEqualTo(task.roundId());
		assertThat(uuidAt(GET("/api/v1/groups/"+w.pid()+"/tasks?cycleId="+w.c2().id(),w.a(),200),"$.items[0].roundId")).isEqualTo(target);
		assertThat((Boolean)at(GET("/api/v1/tasks/"+task.taskId()+"?roundId="+task.roundId(),w.a(),200),"$.isHistorical")).isTrue();
	}
	@Test void revisedDeadlineDoesNotEraseMissedCheckpointsOrBlameReviewerDelay() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Tồn qua hai kỳ","HARD",DL);
		clock.set(w.c1().closesAt().plusSeconds(1));
		MvcResult first=POST("/api/v1/task-rounds/"+task.roundId()+"/carryover",w.lead(),body("targetCycleId",w.c2().id(),"deadline","2026-11-26T10:00:00Z"),201);
		UUID r2=uuidAt(first,"$.targetRound.id"); clock.set(w.c2().closesAt().plusSeconds(1));
		MvcResult second=POST("/api/v1/task-rounds/"+r2+"/carryover",w.lead(),body("targetCycleId",w.cycles().get(2).id(),"deadline","2026-12-03T10:00:00Z"),201);
		UUID r3=uuidAt(second,"$.targetRound.id");
		assertThat((Integer)at(second,"$.task.missedCheckpointCount")).isEqualTo(2);
		UUID plan=uuidAt(second,"$.plan.id");
		POST("/api/v1/task-rounds/"+r3+"/submissions",w.a(),body("planRevisionId",plan,"evidence",List.of(J.o("type","URL","url","https://example.test/result"))),201);
		clock.set(w.cycles().get(2).closesAt().plusSeconds(3600));
		MvcResult waiting=GET("/api/v1/tasks/"+task.taskId(),w.a(),200);
		assertThat((Integer)at(waiting,"$.missedCheckpointCount")).isEqualTo(2);
		assertThat((Boolean)at(waiting,"$.currentlyOverdue")).isFalse();
	}
	@Test void carryoverCannotExceedGroupDeadlineAndFailedDecisionLeavesNoTargetRound() throws Exception {
		World w=world(); Task task=approvedTask(w,w.a(),"Giới hạn hạn dự án","EASY",DL);
		jdbc.sql("UPDATE projects SET deadline=:d WHERE id=:p").param("p",w.pid()).param("d",app.hanare.common.DbTime.toDb(Instant.parse("2026-11-24T16:59:59Z"))).update();
		clock.set(w.c1().closesAt().plusSeconds(1));
		assertThat(code(POST("/api/v1/task-rounds/"+task.roundId()+"/carryover",w.lead(),body("targetCycleId",w.c2().id(),"deadline","2026-11-25T10:00:00Z"),422)))
				.isEqualTo("DEADLINE_AFTER_GROUP_DEADLINE");
		assertThat(jdbc.sql("SELECT count(*) FROM task_review_rounds WHERE task_id=:t").param("t",task.taskId()).query(Long.class).single()).isEqualTo(1);
	}
	@Test void scheduleChangePreservesCyclesWithApprovedCommitments() throws Exception {
		World w=world();
		MvcResult draft=POST("/api/v1/groups/"+w.pid()+"/tasks",w.lead(),body("title","Cam kết ở kỳ tương lai","assigneeId",w.a().id(),"cycleId",w.c2().id(),
				"description","Phạm vi","expectedOutput","Sản phẩm","startAt","2026-11-21T01:00:00Z","deadline","2026-11-27T14:00:00Z","difficulty","EASY"),201);
		UUID plan=uuidAt(draft,"$.pendingRevision.id");
		MvcResult submitted=POST("/api/v1/plans/"+plan+"/submit",w.a(),body("version",0),200);
		POST("/api/v1/plans/"+plan+"/review",w.lead(),body("version",longAt(submitted,"$.plan.version"),"decision","APPROVED"),200);
		POST("/api/v1/groups/"+w.pid()+"/checkpoint-schedules",w.lead(),body("weekday",5,"cutoffTime","20:00","timezone","Asia/Ho_Chi_Minh","scheduleAgreed",true),201);
		assertThat(jdbc.sql("SELECT closes_at FROM checkpoint_cycles WHERE id=:c").param("c",w.c2().id()).query((rs,i)->J.inst(rs,"closes_at")).single()).isEqualTo(w.c2().closesAt());
	}
}
