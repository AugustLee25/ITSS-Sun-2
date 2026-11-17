package app.hanare.task;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.help.HelpDtos;
import app.hanare.help.SupportWorkService;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.task.CycleData.Row;
import app.hanare.task.Scope.CycleRow;
import app.hanare.task.TaskReader.Ctx;
import app.hanare.task.TaskReader.Sub;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Draft persistence is separate; the final transaction checks the exact draft and writes all evidence atomically. */
@Service
public class ReportService {
    public record ItemInput(UUID taskId, String declaredResult, String riskLevel, String note, Instant expectedCompletionAt,
            UUID roundId, UUID planRevisionId, UUID submissionId, List<ResultService.EvidenceInput> evidence, String resultNote) { }
    public record SaveReportRequest(List<ItemInput> items, Long version) { }
    public record SubmitReportRequest(List<ItemInput> items, Long version) { }
    private record Rep(UUID id, int revision, String status, Instant submittedAt, long version) { }
    private record Stored(UUID id, UUID roundId, UUID planId, UUID submissionId, String declared, String risk,
            boolean needsHelp, String note, Instant expectedAt, String pending, String resultNote) {
        String riskLevel() { return needsHelp ? "NEEDS_LEAD_HELP" : "AT_RISK".equals(risk) ? "AT_RISK" : "ON_TRACK"; }
    }
    private record DraftToken(UUID id, long version, List<UUID> missing, Map<String,Object> replay) { }
    private final JdbcClient jdbc;
    private final GroupAccess access;
    private final Scope.Factory scopes;
    private final TaskReader reader;
    private final CycleData data;
    private final TaskViews views;
    private final Ops ops;
    private final TransactionTemplate tx;
    private final SubmissionWriter writer;
    private final WorkflowRequests requests;
    private final ObjectProvider<SupportWorkService> supportWorks;
    private final JsonMapper mapper;
    public ReportService(JdbcClient jdbc, GroupAccess access, Scope.Factory scopes, TaskReader reader, CycleData data,
            TaskViews views, Ops ops, TransactionTemplate tx, SubmissionWriter writer, WorkflowRequests requests,
            ObjectProvider<SupportWorkService> supportWorks, JsonMapper mapper) {
        this.jdbc=jdbc; this.access=access; this.scopes=scopes; this.reader=reader; this.data=data; this.views=views;
        this.ops=ops; this.tx=tx; this.writer=writer; this.requests=requests; this.supportWorks=supportWorks; this.mapper=mapper;
    }
    private List<Rep> reports(UUID cycle, UUID me) {
        return jdbc.sql("SELECT id,revision_no,status,submitted_at,version FROM checkpoint_reports WHERE cycle_id=:c AND member_id=:m ORDER BY revision_no DESC")
                .param("c",cycle).param("m",me).query((rs,i)->new Rep(J.uuid(rs,"id"),rs.getInt("revision_no"),rs.getString("status"),J.inst(rs,"submitted_at"),rs.getLong("version"))).list();
    }
    private Map<UUID,Stored> stored(UUID report) {
        Map<UUID,Stored> out=new LinkedHashMap<>();
        if(report==null) return out;
        jdbc.sql("SELECT *, pending_evidence::text AS pending FROM checkpoint_report_items WHERE report_id=:r ORDER BY created_at,id")
                .param("r",report).query((rs,i)->{ Stored it=new Stored(J.uuid(rs,"id"),J.uuid(rs,"round_id"),J.uuid(rs,"plan_revision_id"),J.uuid(rs,"submission_id"),
                    rs.getString("declared_result"),rs.getString("risk_status"),rs.getBoolean("needs_lead_help"),rs.getString("note"),J.inst(rs,"expected_completion_at"),rs.getString("pending"),rs.getString("pending_result_note")); out.put(it.roundId(),it); return 0; }).list();
        return out;
    }
    private List<ResultService.EvidenceInput> evidence(Stored it) {
        if(it==null) return List.of();
        return Arrays.asList(mapper.readValue(it.pending(), ResultService.EvidenceInput[].class));
    }
    private List<Row> required(Scope s,CycleRow cy,UUID me) { return data.rows(s,cy).stream().filter(r->me.equals(r.assigneeId())).toList(); }
    private Map<String,Object> brief(Scope s,Sub sub) {
        if(sub==null) return null;
        TaskReader.Rev review=reader.reviewOf(sub.id()).orElse(null);
        return J.o("id",sub.id(),"submissionNo",sub.no(),"submittedAt",sub.submittedAt(),"isOnTime",sub.planDeadline()==null||!sub.submittedAt().isAfter(sub.planDeadline()),
                "evidence",views.evidence(sub.evidenceJson()),"reviewDecision",review==null?null:review.decision());
    }
    public Map<String,Object> view(UUID project,UUID cycle,CurrentUser user) { return view(project,cycle,user,null); }
    public Map<String,Object> view(UUID project,UUID cycle,CurrentUser user,Integer revision) {
        access.requireMember(project,user);
        return tx.execute(st->build(scopes.of(project),cycle,user.id(),revision));
    }
    private Map<String,Object> build(Scope s,UUID cycleId,UUID me,Integer revision) {
        CycleRow cy=s.cycle(cycleId);
        boolean closed=cy.isClosed(s.now());
        List<Rep> reps=reports(cycleId,me);
        Rep dr=reps.stream().filter(r->"DRAFT".equals(r.status())).findFirst().orElse(null);
        Rep sent=reps.stream().filter(r->"SUBMITTED".equals(r.status())).findFirst().orElse(null);
        UUID frozenId=jdbc.sql("SELECT report_id_at_cutoff FROM checkpoint_participants WHERE cycle_id=:c AND member_id=:m AND frozen_at IS NOT NULL")
                .param("c",cycleId).param("m",me).query((rs,i)->J.uuid(rs,"report_id_at_cutoff")).optional().orElse(null);
        Rep cur=closed?(data.frozen(cycleId)?reps.stream().filter(r->r.id().equals(frozenId)).findFirst().orElse(null):sent):(dr!=null?dr:sent);
        if(revision!=null) cur=reps.stream().filter(r->r.revision()==revision).findFirst().orElseThrow(()->ApiException.notFound("Không tìm thấy phiên bản báo cáo."));
        Map<UUID,Stored> stored=stored(cur==null?null:cur.id());
        LinkedHashMap<UUID,Row> rows=new LinkedHashMap<>();
        required(s,cy,me).forEach(r->rows.put(r.roundId(),r));
        for(Stored it:stored.values()) if(!rows.containsKey(it.roundId())) {
            Ctx c=reader.require(it.roundId()); TaskReader.Plan p=it.planId()==null?c.effective():reader.plan(it.planId()).orElseThrow();
            rows.put(it.roundId(),new Row(it.roundId(),c.round().taskId(),me,p.title(),p.deadline(),p.difficulty(),null,c));
        }
        List<Map<String,Object>> items=new ArrayList<>(); List<UUID> missing=new ArrayList<>();
        for(Row row:rows.values()) {
            Ctx c=row.ctx(); Stored it=stored.get(row.roundId());
            boolean submittedReport=cur!=null&&"SUBMITTED".equals(cur.status());
            Sub chosen=it!=null?(it.submissionId()==null?null:reader.submission(it.submissionId()).orElse(null)):c.sub();
            UUID knownPlan=it==null?c.round().effectivePlanId():it.planId()!=null?it.planId():chosen==null?null:chosen.planRevisionId();
            TaskReader.Plan p=knownPlan==null?c.effective():reader.plan(knownPlan).orElseThrow();
            List<ResultService.EvidenceInput> pending=evidence(it);
            if(it==null) missing.add(row.taskId());
            TaskReader.Eval eval=data.eval(s,cy,row);
            UUID work=views.extras(List.of(c)).supportWorks().get(row.taskId());
            items.add(J.o("taskId",row.taskId(),"roundId",row.roundId(),"roundNo",c.round().roundNo(),"title",knownPlan==null?row.title():p.title(),"expectedOutput",knownPlan==null?null:p.expectedOutput(),
                    "deadline",knownPlan==null?row.deadline():p.deadline(),"difficulty",knownPlan==null?row.difficulty():p.difficulty(),"taskStatus",eval.taskStatus(),"timeliness",eval.timeliness(),"timelinessLabel",eval.label(),"isLate",eval.isLate(),
                    "submission",brief(s,chosen),"submissionAtCutoff",closed?brief(s,reader.atCutoff(c,cycleId).sub()):null,"planRevisionId",knownPlan,"planReferenceKnown",knownPlan!=null,"currentPlanRevisionId",c.effective().id(),
                    "currentSubmissionId",c.sub()==null?null:c.sub().id(),"supportWorkId",work,"draftEvidence",pending,"draftResultNote",it==null?null:it.resultNote(),
                    "declaredResult",!pending.isEmpty()?"RESULT_SUBMITTED":it!=null?it.declared():chosen==null?"RESULT_MISSING":"RESULT_SUBMITTED",
                    "prefilled",it==null,"riskLevel",it==null?"ON_TRACK":it.riskLevel(),"note",it==null?null:it.note(),"expectedCompletionAt",it==null?null:it.expectedAt(),
                    "canReportDifficulty",!me.equals(s.leaderId())&&!submittedReport&&TaskWritePolicy.canExecute(s,c,TaskWritePolicy.reported(jdbc,row.roundId()))));
        }
        boolean editable=revision==null&&!closed&&!s.now().isBefore(cy.opensAt());
        Map<String,Object> lock=editable?null:J.o("code",s.now().isBefore(cy.opensAt())?"REPORT_WINDOW_NOT_OPEN":"CYCLE_CLOSED","message",s.now().isBefore(cy.opensAt())?"Chưa mở cửa sổ báo cáo.":"Báo cáo chỉ đọc.");
        return J.o("serverTime",s.now(),"cycle",s.cycleDto(cy),"reportId",cur==null?null:cur.id(),"state",cur==null?"NOT_RESPONDED":cur.status(),"revisionNo",cur==null?null:cur.revision(),
                "status",cur==null?null:cur.status(),"submittedAt",cur==null?null:cur.submittedAt(),"canEdit",editable,"lockReason",lock,"items",items,"backlog",backlog(s,cy,me),
                "completeness",J.o("requiredCount",rows.size(),"respondedCount",rows.size()-missing.size(),"missingTaskIds",missing,"isComplete",missing.isEmpty()),
                "submittedLabel",cur!=null&&"SUBMITTED".equals(cur.status())&&missing.isEmpty()?"Đã gửi đầy đủ":null,"version",cur==null?null:cur.version());
    }
    private List<Map<String,Object>> backlog(Scope s,CycleRow cy,UUID me) {
        List<UUID> ids=jdbc.sql("""
                SELECT r.id FROM task_review_rounds r JOIN task_plan_revisions p ON p.id=r.effective_plan_id JOIN checkpoint_cycles c ON c.id=r.cycle_id
                 WHERE r.project_id=:p AND p.assignee_id=:m AND c.closes_at<=:start AND r.state IN ('ACTIVE','AWAITING_REVIEW','CARRYOVER_REQUIRED')
                """).param("p",s.projectId()).param("m",me).param("start",DbTime.toDb(cy.periodStartAt())).query(UUID.class).list();
        return reader.loadAll(ids).values().stream().map(c->{ boolean waiting=c.sub()!=null&&c.rev()==null;
            return J.o("taskId",c.round().taskId(),"roundId",c.round().id(),"title",c.effective().title(),"fromCycle",s.cycleRef(c.round().cycleId()),
                "state",waiting?"AWAITING_REVIEW_PREVIOUS_CYCLE":"CARRYOVER_REQUIRED","message",waiting?"Chờ TL duyệt ở kỳ trước":"Cần TL chốt lịch xử lý tiếp"); }).toList();
    }
    private void checkWindow(CycleRow cy,Instant now) {
        if(now.isBefore(cy.opensAt())) throw ApiException.of(HttpStatus.CONFLICT,"REPORT_WINDOW_NOT_OPEN","Chưa mở báo cáo.");
        if(cy.isClosed(now)) throw ApiException.of(HttpStatus.CONFLICT,"CYCLE_CLOSED","Kỳ đã khóa.");
    }
    public Map<String,Object> save(UUID project,UUID cycle,CurrentUser user,SaveReportRequest request) {
        access.requireMember(project,user);
        if(request.items()==null) throw ApiException.validation("items","Bắt buộc.");
        return tx.execute(st->{ writeDraft(project,cycle,user.id(),request.items(),request.version()); return build(scopes.of(project),cycle,user.id(),null); });
    }
    public Map<String,Object> submit(UUID project,UUID cycle,CurrentUser user,SubmitReportRequest request) { return submit(project,cycle,user,request,null); }
    public Map<String,Object> submit(UUID project,UUID cycle,CurrentUser user,SubmitReportRequest request,String key) {
        access.requireMember(project,user);
        String operation="report-submit:"+cycle;
        DraftToken token=tx.execute(st->{
            WorkflowLocks.project(jdbc,project);
            Map<String,Object> replay=requests.replay(user.id(),operation,key,request);
            if(replay!=null) return new DraftToken(null,0,List.of(),replay);
            Rep dr=writeDraft(project,cycle,user.id(),request.items(),request.version());
            if("SUBMITTED".equals(dr.status())) return new DraftToken(null,0,List.of(),build(scopes.of(project),cycle,user.id(),null));
            Set<UUID> have=stored(dr.id()).keySet();
            List<UUID> missing=required(scopes.of(project),scopes.of(project).cycle(cycle),user.id()).stream().filter(r->!have.contains(r.roundId())).map(Row::taskId).toList();
            return new DraftToken(dr.id(),dr.version(),missing,null);
        });
        if(token.replay()!=null) return token.replay();
        if(!token.missing().isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"REPORT_INCOMPLETE","Còn task chưa khai báo. Nháp đã được lưu.",
                token.missing().stream().map(id->new ApiError.FieldError("items["+id+"]","Chưa khai báo")).toList());
        return tx.execute(st->{
            WorkflowLocks.project(jdbc,project);
            Map<String,Object> replay=requests.replay(user.id(),operation,key,request); if(replay!=null) return replay;
            Scope s=scopes.of(project); CycleRow cy=s.cycle(cycle); checkWindow(cy,ops.now());
            Rep dr=reports(cycle,user.id()).stream().filter(r->r.id().equals(token.id())).findFirst().orElseThrow(ApiException::versionConflict);
            if(!"DRAFT".equals(dr.status())||dr.version()!=token.version()) throw ApiException.versionConflict();
            Map<UUID,Stored> values=stored(dr.id()); List<Row> required=required(s,cy,user.id());
            if(required.size()!=values.size()||required.stream().anyMatch(r->!values.containsKey(r.roundId()))) throw ApiException.versionConflict();
            for(Row row:required) validateFinal(s,row,values.get(row.roundId()));
            for(Row row:required) {
                Stored it=values.get(row.roundId()); List<ResultService.EvidenceInput> pending=evidence(it);
                if(!pending.isEmpty()) {
                    UUID work=views.extras(List.of(row.ctx())).supportWorks().get(row.taskId());
                    if(work==null) writer.write(row.roundId(),user,new ResultService.SubmitResultRequest(row.ctx().effective().id(),it.resultNote(),pending),true);
                    else supportWorks.getObject().submitForReport(work,user,new HelpDtos.SubmitSupportResultRequest(
                            it.resultNote()==null||it.resultNote().isBlank()?"Kết quả gửi qua báo cáo checkpoint.":it.resultNote(),
                            pending.stream().map(e->new HelpDtos.EvidenceInput(e.type(),e.url(),e.label())).toList(),null));
                    UUID submission=reader.require(row.roundId()).sub().id();
                    jdbc.sql("UPDATE checkpoint_report_items SET declared_result='RESULT_SUBMITTED',submission_id=:s,pending_evidence='[]'::jsonb WHERE id=:i")
                            .param("s",submission).param("i",it.id()).update();
                }
            }
            checkWindow(scopes.of(project).cycle(cycle),ops.now());
            jdbc.sql("UPDATE checkpoint_reports SET status='SUBMITTED',submitted_at=:at,version=version+1 WHERE id=:id")
                    .param("at",DbTime.toDb(ops.now())).param("id",dr.id()).update();
            ops.audit(project,user.id(),"CHECKPOINT_REPORT",dr.id(),"REPORT_SUBMITTED",null,J.o("cycleId",cycle,"revisionNo",dr.revision()));
            Map<String,Object> response=build(scopes.of(project),cycle,user.id(),null);
            requests.save(project,user.id(),operation,key,request,response);
            return response;
        });
    }
    private void validateFinal(Scope s,Row row,Stored it) {
        String field="items["+row.taskId()+"]";
        Ctx c=reader.require(row.roundId());
        if(c.open()!=null) throw ApiException.of(HttpStatus.CONFLICT,"REVISION_ALREADY_IN_FLIGHT","Hãy xử lý hoặc hủy đề xuất kế hoạch trước khi gửi báo cáo.");
        if(it.planId()!=null&&!it.planId().equals(c.round().effectivePlanId())) throw ApiException.versionConflict();
        if(!Objects.equals(it.submissionId(),c.sub()==null?null:c.sub().id())) throw ApiException.versionConflict();
        List<ResultService.EvidenceInput> pending=evidence(it);
        if(!pending.isEmpty()) {
            try { ResultService.validateEvidence(new ResultService.SubmitResultRequest(c.round().effectivePlanId(),it.resultNote(),pending)); }
            catch(ApiException e) { throw new ApiException(e.status(),e.code(),e.getMessage(),List.of(new ApiError.FieldError(field+".evidence",e.getMessage()))); }
        } else if(c.sub()==null) {
            J.Check check=J.check().len(it.note(),field+".note",1,1000)
                    .require(it.expectedAt()!=null,field+".expectedCompletionAt","Nhập ngày giờ dự kiến."); check.done();
            if(!it.expectedAt().isAfter(ops.now())) throw ApiException.validation(field+".expectedCompletionAt","Ngày dự kiến phải ở tương lai.");
            if(it.expectedAt().isAfter(new TaskDeadline(jdbc).groupLimit(s.projectId())))
                throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY,"DEADLINE_AFTER_GROUP_DEADLINE","Ngày dự kiến vượt hạn nhóm.");
            if(s.containing(it.expectedAt()).isEmpty()) throw ApiException.validation(field+".expectedCompletionAt","Chưa có kỳ chứa ngày đề xuất. Chọn ngày trong kỳ hiện có hoặc tải lại lịch.");
        }
        if((c.sub()!=null||!pending.isEmpty())&&it.needsHelp()) throw ApiException.validation(field+".riskLevel","Đã nộp kết quả: không mở hỗ trợ mới.");
    }
    private Rep writeDraft(UUID project,UUID cycle,UUID me,List<ItemInput> input,Long version) {
        WorkflowLocks.project(jdbc,project); Scope s=scopes.of(project); CycleRow cy=s.cycle(cycle); checkWindow(cy,ops.now());
        List<Rep> reps=reports(cycle,me); Rep base=reps.isEmpty()?null:reps.get(0);
        if(base==null?version!=null:version==null||version!=base.version()) throw ApiException.versionConflict();
        if(input==null) {
            if(base==null) throw ApiException.validation("items","Hãy xác nhận các task hoặc lưu nháp trước khi gửi.");
            return base;
        }
        List<Row> required=required(s,cy,me); Map<UUID,Row> byTask=new HashMap<>(); required.forEach(r->byTask.put(r.taskId(),r));
        Set<UUID> seen=new HashSet<>(); J.Check check=J.check();
        for(int i=0;i<input.size();i++) {
            ItemInput it=input.get(i); String f="items["+i+"]";
            if(it==null||it.taskId()==null||!byTask.containsKey(it.taskId())) { check.add(f+".taskId","Task không thuộc nghĩa vụ của bạn trong kỳ."); continue; }
            Row row=byTask.get(it.taskId());
            check.require(seen.add(it.taskId()),f+".taskId","Trùng task.");
            check.require(it.declaredResult()!=null&&List.of("RESULT_MISSING","RESULT_SUBMITTED").contains(it.declaredResult()),f+".declaredResult","Khai báo không hợp lệ.");
            check.require(it.riskLevel()!=null&&List.of("ON_TRACK","AT_RISK","NEEDS_LEAD_HELP").contains(it.riskLevel()),f+".riskLevel","Mức rủi ro không hợp lệ.");
            if(it.note()!=null) check.len(it.note(),f+".note",0,1000);
            if(it.resultNote()!=null) check.len(it.resultNote(),f+".resultNote",0,2000);
            if(it.evidence()!=null) check.require(it.evidence().size()<=5,f+".evidence","Tối đa 5 link.");
            if(it.roundId()!=null&&!row.roundId().equals(it.roundId())||it.planRevisionId()!=null&&!row.ctx().effective().id().equals(it.planRevisionId())) throw ApiException.versionConflict();
            if(it.roundId()!=null&&!Objects.equals(it.submissionId(),row.ctx().sub()==null?null:row.ctx().sub().id())) throw ApiException.versionConflict();
            boolean result=row.ctx().sub()!=null||(it.evidence()!=null&&!it.evidence().isEmpty());
            if(("RESULT_SUBMITTED".equals(it.declaredResult()))!=result) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"RESULT_DECLARATION_MISMATCH","Khai báo không khớp bản nộp/minh chứng.",List.of(new ApiError.FieldError(f+".declaredResult","Không khớp lần nộp thật.")));
        }
        check.done();
        UUID participant=jdbc.sql("INSERT INTO checkpoint_participants(cycle_id,project_id,member_id) VALUES(:c,:p,:m) ON CONFLICT(cycle_id,member_id) DO UPDATE SET cycle_id=EXCLUDED.cycle_id RETURNING id")
                .param("c",cycle).param("p",project).param("m",me).query(UUID.class).single();
        UUID report;
        if(base==null||"SUBMITTED".equals(base.status())) report=jdbc.sql("INSERT INTO checkpoint_reports(participant_id,cycle_id,member_id,revision_no,status,created_at,version) VALUES(:pt,:c,:m,:n,'DRAFT',:at,:v) RETURNING id")
                .param("pt",participant).param("c",cycle).param("m",me).param("n",base==null?1:base.revision()+1)
                .param("v",base==null?0:base.version()+1).param("at",DbTime.toDb(ops.now())).query(UUID.class).single();
        else {
            report=base.id(); jdbc.sql("UPDATE checkpoint_reports SET version=version+1 WHERE id=:r").param("r",report).update();
            jdbc.sql("DELETE FROM checkpoint_report_items WHERE report_id=:r").param("r",report).update();
        }
        for(ItemInput it:input) {
            Row row=byTask.get(it.taskId()); Ctx c=row.ctx(); TaskReader.Plan plan=c.effective();
            UUID snapshot=jdbc.sql("""
                    INSERT INTO checkpoint_task_snapshots(cycle_id,project_id,round_id,task_id,assignee_id,approved_plan_id,title_snapshot,deadline_snapshot,difficulty_snapshot)
                    VALUES(:c,:p,:r,:t,:m,:plan,:title,:dl,:df) ON CONFLICT(cycle_id,round_id) DO UPDATE SET cycle_id=EXCLUDED.cycle_id RETURNING id
                    """).param("c",cycle).param("p",project).param("r",row.roundId()).param("t",row.taskId()).param("m",me).param("plan",plan.id())
                    .param("title",plan.title()).param("dl",DbTime.toDb(plan.deadline())).param("df",plan.difficulty()).query(UUID.class).single();
            jdbc.sql("""
                    INSERT INTO checkpoint_report_items(report_id,cycle_id,member_id,snapshot_id,round_id,plan_revision_id,declared_result,submission_id,risk_status,needs_lead_help,note,expected_completion_at,pending_evidence,pending_result_note)
                    VALUES(:rep,:c,:m,:snap,:r,:plan,:dec,:sub,:risk,:help,:note,:ec,CAST(:ev AS jsonb),:rn)
                    """).param("rep",report).param("c",cycle).param("m",me).param("snap",snapshot).param("r",row.roundId()).param("plan",plan.id())
                    .param("dec",c.sub()==null?"RESULT_MISSING":"RESULT_SUBMITTED").param("sub",c.sub()==null?null:c.sub().id())
                    .param("risk","ON_TRACK".equals(it.riskLevel())?"NORMAL":"AT_RISK").param("help","NEEDS_LEAD_HELP".equals(it.riskLevel()))
                    .param("note",it.note()).param("ec",DbTime.toDb(it.expectedCompletionAt())).param("ev",mapper.writeValueAsString(it.evidence()==null?List.of():it.evidence()))
                    .param("rn",it.resultNote()).update();
        }
        return reports(cycle,me).stream().filter(r->r.id().equals(report)).findFirst().orElseThrow();
    }
}
