package app.hanare.task;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Outstanding checkpoints follow the task through carryover; waiting for a reviewer never adds delay. */
@Component
public class MissedCheckpoints {
	private final JdbcClient jdbc;
	private final TaskReader reader;
	public MissedCheckpoints(JdbcClient jdbc, TaskReader reader) { this.jdbc=jdbc; this.reader=reader; }
	public int count(Scope s, UUID taskId, Instant asOf) {
		List<UUID> ids=jdbc.sql("SELECT id FROM task_review_rounds WHERE task_id=:t ORDER BY round_no").param("t",taskId).query(UUID.class).list();
		List<TaskReader.Ctx> history=new ArrayList<>(reader.loadAll(ids).values());
		Set<UUID> missed=new HashSet<>(); Instant first=null; Instant acceptedAt=null;
		for(TaskReader.Ctx c:history) {
			if(!c.committed()) continue;
			Scope.CycleRow cy=s.cycle(c.round().cycleId());
			if(c.round().acceptedAt()!=null&&!c.round().acceptedAt().isAfter(asOf)) acceptedAt=c.round().acceptedAt();
			if(cy.closesAt().isAfter(asOf)) continue;
			boolean noTimelyResult=c.sub()==null||c.sub().submittedAt().isAfter(c.deadline());
			boolean returned=c.rev()!=null&&!"ACCEPTED".equals(c.rev().decision())&&!c.rev().reviewedAt().isAfter(asOf);
			if(noTimelyResult||returned) { missed.add(cy.id()); if(first==null||cy.closesAt().isBefore(first)) first=cy.closesAt(); }
		}
		if(first==null) return 0;
		for(Scope.CycleRow cy:s.allCycles()) {
			if("CANCELLED".equals(cy.status())||cy.closesAt().isBefore(first)||cy.closesAt().isAfter(asOf)
					||acceptedAt!=null&&cy.closesAt().isAfter(acceptedAt)) continue;
			boolean waiting=history.stream().anyMatch(c->c.sub()!=null&&!c.sub().submittedAt().isAfter(cy.closesAt())
					&&(c.rev()==null||c.rev().reviewedAt().isAfter(cy.closesAt())));
			if(!waiting) missed.add(cy.id());
		}
		return missed.size();
	}
}
