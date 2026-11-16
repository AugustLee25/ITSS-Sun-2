package app.hanare.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.hanare.common.DbTime;
import app.hanare.testsupport.AbstractDbTest;
import app.hanare.testsupport.DbFixtures.CycleRef;
import app.hanare.testsupport.DbFixtures.GroupRef;
import app.hanare.testsupport.DbFixtures.PointsScenario;
import app.hanare.testsupport.DbFixtures.TaskRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Các ràng buộc quan trọng của spec 12.3-12.8 + nghiệm thu 12.10 ở mức DB. */
class ConstraintsTest extends AbstractDbTest {

	private static void rejected(ThrowingCallable call, String messagePart) {
		assertThatThrownBy(call).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(messagePart);
	}

	// ------------------------------------------------------------------ nhóm, lịch, kỳ

	@Test
	void emailUniqueAndNormalized() {
		var u = fx.user("Dup", "dup.email@example.test");
		rejected(() -> fx.user("Dup2", "dup.email@example.test"), "users_email_key");
		rejected(() -> fx.user("Upper", "Upper.Case@example.test"), "users_email_normalized");
		assertThat(u.id()).isNotNull();
	}

	@Test
	void onlyOneActiveLeaderPerGroup() {
		GroupRef g = fx.group("Leader unique", 3);
		UUID member = g.members().get(0).id();
		rejected(() -> jdbc.sql("UPDATE project_members SET role = 'LEAD' WHERE project_id = :p AND user_id = :u")
				.param("p", g.projectId()).param("u", member).update(), "uq_project_members_one_active_leader");
		// chuyển TL đúng thứ tự: hạ TL cũ trước, rồi nâng TL mới
		jdbc.sql("UPDATE project_members SET role = 'MEMBER' WHERE project_id = :p AND user_id = :u")
				.param("p", g.projectId()).param("u", g.leader().id()).update();
		jdbc.sql("UPDATE project_members SET role = 'LEAD' WHERE project_id = :p AND user_id = :u")
				.param("p", g.projectId()).param("u", member).update();
	}

	@Test
	void cycleBoundariesAreChainedUniqueAndGapFree() {
		GroupRef g = fx.group("Cycles", 3);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		List<CycleRef> cycles = fx.weeklyCycles(g.projectId(), schedule, 2);
		CycleRef c1 = cycles.get(0);
		CycleRef c2 = cycles.get(1);

		// UNIQUE(project, sequence_no)
		rejected(() -> fx.cycle(g.projectId(), schedule, 2, c2.id(), c2.closesAt(), c2.closesAt().plus(DbFixturesWeek.WEEK),
				"SCHEDULED"), "checkpoint_cycles_project_seq_key");
		// hai kỳ cùng nối tiếp một kỳ -> chồng lấn
		rejected(() -> fx.cycle(g.projectId(), schedule, 3, c1.id(), c1.closesAt(), c1.closesAt().plus(Duration.ofDays(3)),
				"SCHEDULED"), "uq_checkpoint_cycles_next");
		// hở một khoảng giữa hai kỳ (period_start_at != closes_at của kỳ trước)
		rejected(() -> fx.cycle(g.projectId(), schedule, 3, c2.id(), c2.closesAt().plusSeconds(3600),
				c2.closesAt().plus(DbFixturesWeek.WEEK), "SCHEDULED"), "fk_cycle_previous");
		// hai kỳ đầu
		rejected(() -> fx.cycle(g.projectId(), schedule, 9, null, c1.periodStart().minus(DbFixturesWeek.WEEK),
				c1.periodStart(), "SCHEDULED"), "uq_checkpoint_cycles_first");
		// nối đúng -> hợp lệ
		fx.cycle(g.projectId(), schedule, 3, c2.id(), c2.closesAt(), c2.closesAt().plus(DbFixturesWeek.WEEK), "SCHEDULED");
	}

	@Test
	void cycleStatusOnlyMovesForwardAndBoundariesFreezeAfterOpen() {
		GroupRef g = fx.group("Cycle status", 2);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		CycleRef c1 = fx.weeklyCycles(g.projectId(), schedule, 1).get(0);

		rejected(() -> jdbc.sql("UPDATE checkpoint_cycles SET status = 'FINALIZED', finalized_at = now() WHERE id = :id")
				.param("id", c1.id()).update(), "invalid status transition");
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'OPEN' WHERE id = :id").param("id", c1.id()).update();
		rejected(() -> jdbc.sql("UPDATE checkpoint_cycles SET closes_at = closes_at + interval '1 day' WHERE id = :id")
				.param("id", c1.id()).update(), "immutable");
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'CLOSED' WHERE id = :id").param("id", c1.id()).update();
		jdbc.sql("UPDATE checkpoint_cycles SET status = 'FINALIZED', finalized_at = now() WHERE id = :id")
				.param("id", c1.id()).update();
		rejected(() -> jdbc.sql("DELETE FROM checkpoint_cycles WHERE id = :id").param("id", c1.id()).update(),
				"Hard delete");
	}

	@Test
	void checkpointScheduleIsImmutable() {
		GroupRef g = fx.group("Schedule immutable", 2);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		rejected(() -> jdbc.sql("UPDATE checkpoint_schedules SET weekday = 7 WHERE id = :id").param("id", schedule).update(),
				"immutable");
	}

	// ------------------------------------------------------------------ task, kế hoạch, nộp bài

	@Test
	void deadlineEqualToCutoffBelongsToThisCycleAndSundayBelongsToNext() {
		GroupRef g = fx.group("Deadline boundary", 3);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		List<CycleRef> cycles = fx.weeklyCycles(g.projectId(), schedule, 2);
		CycleRef c1 = cycles.get(0);
		CycleRef c2 = cycles.get(1);
		UUID member = g.members().get(0).id();

		// đúng cutoff 22:00 thứ Sáu 20/11 thuộc kỳ 1
		fx.approvedTask(g.projectId(), g.leader().id(), member, c1, "MEDIUM", c1.closesAt());
		// Chủ nhật 22/11 22:00 không thuộc kỳ 1 (nhưng thuộc kỳ 2)
		Instant sunday = c1.closesAt().plus(Duration.ofDays(2));
		rejected(() -> fx.approvedTask(g.projectId(), g.leader().id(), member, c1, "MEDIUM", sunday), "outside the cycle");
		fx.approvedTask(g.projectId(), g.leader().id(), member, c2, "MEDIUM", sunday);
		// mốc bắt đầu kỳ (loại trừ) không thuộc kỳ 1
		rejected(() -> fx.approvedTask(g.projectId(), g.leader().id(), member, c1, "EASY", c1.periodStart()),
				"outside the cycle");
	}

	@Test
	void assigneeMustBelongToTheSameGroup() {
		GroupRef a = fx.group("Group A", 2);
		GroupRef b = fx.group("Group B", 2);
		UUID scheduleA = fx.schedule(a.projectId(), a.leader().id());
		CycleRef cycleA = fx.weeklyCycles(a.projectId(), scheduleA, 1).get(0);
		rejected(() -> fx.approvedTask(a.projectId(), a.leader().id(), b.members().get(0).id(), cycleA, "EASY",
				cycleA.closesAt()), "fk_revision_assignee");
	}

	@Test
	void roundAndRevisionRules() {
		GroupRef g = fx.group("Rounds", 3);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		List<CycleRef> cycles = fx.weeklyCycles(g.projectId(), schedule, 2);
		UUID member = g.members().get(0).id();
		TaskRef t = fx.approvedTask(g.projectId(), g.leader().id(), member, cycles.get(0), "HARD", cycles.get(0).closesAt());

		// bản APPROVED bất biến
		rejected(() -> jdbc.sql("UPDATE task_plan_revisions SET title = 'đổi' WHERE id = :id").param("id", t.planId()).update(),
				"immutable");
		// một bản nháp mỗi vòng
		jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, base_revision_id, created_by)
				VALUES (:p, :r, 2, :base, :c)
				""").param("p", g.projectId()).param("r", t.roundId()).param("base", t.planId()).param("c", member).update();
		rejected(() -> jdbc.sql("""
				INSERT INTO task_plan_revisions (project_id, round_id, revision_no, created_by)
				VALUES (:p, :r, 3, :c)
				""").param("p", g.projectId()).param("r", t.roundId()).param("c", member).update(),
				"uq_task_plan_revisions_one_open");

		// chỉ một vòng chưa kết thúc / task: vòng 2 chỉ tạo được sau khi vòng 1 CARRIED_OVER
		String insertRound2 = """
				INSERT INTO task_review_rounds (project_id, task_id, round_no, cycle_id, previous_round_id, reason, created_by)
				VALUES (:p, :t, 2, :c, :prev, 'FIX_REQUIRED', :u)
				""";
		rejected(() -> jdbc.sql(insertRound2).param("p", g.projectId()).param("t", t.taskId())
				.param("c", cycles.get(1).id()).param("prev", t.roundId()).param("u", g.leader().id()).update(),
				"uq_task_review_rounds_one_open");
		jdbc.sql("UPDATE task_review_rounds SET state = 'CARRIED_OVER', closed_at = now() WHERE id = :id")
				.param("id", t.roundId()).update();
		jdbc.sql(insertRound2).param("p", g.projectId()).param("t", t.taskId()).param("c", cycles.get(1).id())
				.param("prev", t.roundId()).param("u", g.leader().id()).update();
		// không tạo hai vòng tiếp theo cho cùng vòng cũ
		rejected(() -> jdbc.sql(insertRound2.replace(", 2, :c,", ", 3, :c,"))
				.param("p", g.projectId()).param("t", t.taskId()).param("c", cycles.get(1).id())
				.param("prev", t.roundId()).param("u", g.leader().id()).update(), "task_review_rounds");
	}

	@Test
	void submissionsNeedEvidenceAndAreImmutable() {
		GroupRef g = fx.group("Submissions", 3);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		CycleRef c1 = fx.weeklyCycles(g.projectId(), schedule, 1).get(0);
		TaskRef t = fx.approvedTask(g.projectId(), g.leader().id(), g.members().get(0).id(), c1, "EASY", c1.closesAt());

		rejected(() -> jdbc.sql("""
				INSERT INTO task_submissions (project_id, round_id, plan_revision_id, submission_no, submitted_by, evidence)
				VALUES (:p, :r, :plan, 1, :by, CAST('[]' AS jsonb))
				""").param("p", g.projectId()).param("r", t.roundId()).param("plan", t.planId())
				.param("by", t.assigneeId()).update(), "task_submissions_evidence_chk");
		// nộp 19:30, deadline 20:00 (giờ VN)
		Instant deadline = c1.closesAt().minus(Duration.ofHours(2));
		UUID sub = fx.submission(g.projectId(), t, 1, deadline.minus(Duration.ofMinutes(30)));
		rejected(() -> fx.submission(g.projectId(), t, 1, deadline), "task_submissions_round_no_key");
		rejected(() -> jdbc.sql("UPDATE task_submissions SET result_note = 'sửa' WHERE id = :id").param("id", sub).update(),
				"immutable");
		// quyết định sửa bắt buộc có mô tả; mỗi lần nộp tối đa một quyết định
		rejected(() -> jdbc.sql("""
				INSERT INTO task_result_reviews (project_id, submission_id, decision, reviewer_id)
				VALUES (:p, :s, 'FIX_REQUIRED', :r)
				""").param("p", g.projectId()).param("s", sub).param("r", g.leader().id()).update(),
				"task_result_reviews_issue_chk");
		fx.review(g.projectId(), sub, g.leader().id(), "FIX_REQUIRED", c1.closesAt().plusSeconds(60));
		rejected(() -> fx.review(g.projectId(), sub, g.leader().id(), "ACCEPTED", c1.closesAt().plusSeconds(120)),
				"task_result_reviews_submission_key");
	}

	// ------------------------------------------------------------------ báo cáo kỳ

	@Test
	void submittedReportIsImmutableAndItemsMustBelongToTheReportOwner() {
		GroupRef g = fx.group("Reports", 4);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		CycleRef c1 = fx.weeklyCycles(g.projectId(), schedule, 1).get(0);
		UUID a = g.members().get(0).id();
		UUID b = g.members().get(1).id();
		TaskRef taskOfA = fx.approvedTask(g.projectId(), g.leader().id(), a, c1, "EASY", c1.closesAt());
		UUID snapshot = jdbc.sql("""
				INSERT INTO checkpoint_task_snapshots (cycle_id, project_id, round_id, task_id, assignee_id, approved_plan_id,
				    title_snapshot, deadline_snapshot, difficulty_snapshot)
				VALUES (:c, :p, :r, :t, :a, :plan, 'Task A', :dl, 'EASY') RETURNING id
				""").param("c", c1.id()).param("p", g.projectId()).param("r", taskOfA.roundId())
				.param("t", taskOfA.taskId()).param("a", a).param("plan", taskOfA.planId())
				.param("dl", DbTime.toDb(c1.closesAt())).query(UUID.class).single();
		UUID participantA = participant(c1, g, a);
		UUID participantB = participant(c1, g, b);
		UUID reportA = report(participantA, c1, a);
		UUID reportB = report(participantB, c1, b);
		String insertItem = """
				INSERT INTO checkpoint_report_items (report_id, cycle_id, member_id, snapshot_id, round_id, declared_result)
				VALUES (:rep, :c, :m, :s, :round, 'RESULT_MISSING')
				""";
		// B không được khai task của A
		rejected(() -> jdbc.sql(insertItem).param("rep", reportB).param("c", c1.id()).param("m", b).param("s", snapshot)
				.param("round", taskOfA.roundId()).update(), "fk_report_item_snapshot");
		// RESULT_SUBMITTED phải kèm lần nộp thật
		rejected(() -> jdbc.sql(insertItem.replace("'RESULT_MISSING'", "'RESULT_SUBMITTED'")).param("rep", reportA)
				.param("c", c1.id()).param("m", a).param("s", snapshot).param("round", taskOfA.roundId()).update(),
				"checkpoint_report_items_evidence_chk");
		jdbc.sql(insertItem).param("rep", reportA).param("c", c1.id()).param("m", a).param("s", snapshot)
				.param("round", taskOfA.roundId()).update();
		// chỉ một bản nháp/participant
		rejected(() -> report(participantA, c1, a, 2), "uq_checkpoint_reports_one_draft");
		// gửi xong thì bất biến, kể cả các mục
		jdbc.sql("UPDATE checkpoint_reports SET status = 'SUBMITTED', submitted_at = :at WHERE id = :id")
				.param("at", DbTime.toDb(c1.closesAt().minusSeconds(60))).param("id", reportA).update();
		rejected(() -> jdbc.sql("UPDATE checkpoint_reports SET status = 'DRAFT', submitted_at = NULL WHERE id = :id")
				.param("id", reportA).update(), "immutable");
		rejected(() -> jdbc.sql("UPDATE checkpoint_report_items SET note = 'sửa' WHERE report_id = :id").param("id", reportA)
				.update(), "immutable");
		// snapshot chưa đóng băng sửa được, đã đóng băng thì bất biến
		jdbc.sql("UPDATE checkpoint_task_snapshots SET frozen_at = now() WHERE id = :id").param("id", snapshot).update();
		rejected(() -> jdbc.sql("UPDATE checkpoint_task_snapshots SET title_snapshot = 'đổi' WHERE id = :id")
				.param("id", snapshot).update(), "immutable");
	}

	private UUID participant(CycleRef cycle, GroupRef g, UUID memberId) {
		return jdbc.sql("INSERT INTO checkpoint_participants (cycle_id, project_id, member_id) VALUES (:c, :p, :m) RETURNING id")
				.param("c", cycle.id()).param("p", g.projectId()).param("m", memberId).query(UUID.class).single();
	}

	private UUID report(UUID participantId, CycleRef cycle, UUID memberId) {
		return report(participantId, cycle, memberId, 1);
	}

	private UUID report(UUID participantId, CycleRef cycle, UUID memberId, int revisionNo) {
		return jdbc.sql("""
				INSERT INTO checkpoint_reports (participant_id, cycle_id, member_id, revision_no) VALUES (:p, :c, :m, :n)
				RETURNING id
				""").param("p", participantId).param("c", cycle.id()).param("m", memberId).param("n", revisionNo)
				.query(UUID.class).single();
	}

	// ------------------------------------------------------------------ điểm

	@Test
	void pointScenarioMatchesSpecExample() {
		PointsScenario s = fx.pointsScenarioFiveMembers();
		// 3,3333 / 6,6667 / 10,0000 điểm % = 33.333 / 66.667 / 100.000 units; tổng 20% = 200.000 units
		List<Long> units = jdbc.sql("""
				SELECT point_units FROM task_point_budgets WHERE project_id = :p ORDER BY point_units
				""").param("p", s.group().projectId()).query(Long.class).list();
		assertThat(units).containsExactly(33_333L, 66_667L, 100_000L);
		assertThat(units.stream().mapToLong(Long::longValue).sum()).isEqualTo(200_000L);
		// tổng quỹ khởi đầu 5 người = 1.000.000 units = 100%
		assertThat(jdbc.sql("SELECT sum(initial_units) FROM member_point_budgets WHERE project_id = :p")
				.param("p", s.group().projectId()).query(Long.class).single()).isEqualTo(1_000_000L);
	}

	@Test
	void policyActivationRequiresBudgetsTotalling100Percent() {
		GroupRef g = fx.group("Bad policy", 3);
		UUID policy = fx.pointPolicyDraft(g.projectId(), g.leader().id(), 3);
		fx.memberBudget(policy, g.projectId(), g.leader().id(), 400_000, 400_000, 0);
		fx.memberBudget(policy, g.projectId(), g.members().get(0).id(), 300_000, 300_000, 0);
		fx.memberBudget(policy, g.projectId(), g.members().get(1).id(), 200_000, 200_000, 0); // tổng 900.000
		rejected(() -> fx.activatePolicy(policy, g.leader().id()), "must total");
		// tạo thẳng ở trạng thái ACTIVE cũng bị chặn
		rejected(() -> jdbc.sql("""
				INSERT INTO point_policies (project_id, version_no, member_count_at_baseline, status, created_by, approved_by, approved_at)
				VALUES (:p, 9, 3, 'ACTIVE', :u, :u, now())
				""").param("p", g.projectId()).param("u", g.leader().id()).update(), "create as DRAFT");
	}

	@Test
	void onePointAwardPerTaskAndLedgerRules() {
		PointsScenario s = fx.pointsScenarioFiveMembers();
		UUID project = s.group().projectId();
		UUID lead = s.group().leader().id();
		UUID a = s.memberA().id();
		CycleRef cycle = s.cycle();
		Instant afterCutoff = cycle.closesAt().plus(Duration.ofHours(10));

		// task Khó (index 2): nộp 19:30 (giờ VN) ngày deadline, TL duyệt sau checkpoint
		UUID subHard = fx.submitAndAccept(s, 2, cycle.closesAt().minus(Duration.ofHours(2)), afterCutoff);
		// task Dễ (index 0): TL yêu cầu sửa -> chưa được ghi điểm
		UUID subEasy = fx.submission(project, s.tasks().get(0), 1, cycle.closesAt().minus(Duration.ofHours(3)));
		fx.review(project, subEasy, lead, "FIX_REQUIRED", afterCutoff);
		UUID budgetEasy = s.budgets().get(0);
		UUID budgetHard = s.budgets().get(2);

		rejected(() -> fx.settlement(project, budgetEasy, s.assignments().get(0), a, subEasy, lead), "not been ACCEPTED");
		// lần nộp của task khác không dùng được cho quỹ này
		rejected(() -> fx.settlement(project, budgetEasy, s.assignments().get(0), a, subHard, lead), "does not belong");
		// không tự duyệt điểm mình nhận
		rejected(() -> fx.settlement(project, budgetHard, s.assignments().get(2), a, subHard, a),
				"task_point_settlements_not_self_chk");

		UUID settlement = fx.settlement(project, budgetHard, s.assignments().get(2), a, subHard, lead);
		// mỗi budget chỉ một settlement hiệu lực
		rejected(() -> fx.settlement(project, budgetHard, s.assignments().get(2), a, subHard, lead),
				"uq_task_point_settlements_one_active");
		// AWARD phải đúng +P_t
		rejected(() -> fx.ledgerAward(project, budgetHard, settlement, a, 50_000, "k-wrong"), "exactly");
		fx.ledgerAward(project, budgetHard, settlement, a, 100_000, "award-hard-1");
		// không cấp hai khoản cho cùng settlement / cùng task, kể cả khi đổi idempotency key
		rejected(() -> fx.ledgerAward(project, budgetHard, settlement, a, 100_000, "award-hard-2"), "point_ledger_entries");
		// ledger append-only
		rejected(() -> jdbc.sql("UPDATE point_ledger_entries SET delta_units = 1 WHERE idempotency_key = 'award-hard-1'")
				.update(), "append-only");
		rejected(() -> jdbc.sql("DELETE FROM point_ledger_entries").update(), "append-only");
		assertThat(jdbc.sql("SELECT sum(delta_units) FROM point_ledger_entries WHERE budget_id = :b")
				.param("b", budgetHard).query(Long.class).single()).isEqualTo(100_000L);

		// idempotency_key duy nhất toàn cục (settlement hợp lệ khác dùng lại key cũ)
		UUID subMedium = fx.submitAndAccept(s, 1, cycle.closesAt().minus(Duration.ofHours(4)), afterCutoff);
		UUID settlementMedium = fx.settlement(project, s.budgets().get(1), s.assignments().get(1), a, subMedium, lead);
		rejected(() -> fx.ledgerAward(project, s.budgets().get(1), settlementMedium, a, 66_667, "award-hard-1"),
				"point_ledger_entries_idem_key");
	}

	@Test
	void correctionIsReversalThenNewSettlementNeverEditOrDelete() {
		PointsScenario s = fx.pointsScenarioFiveMembers();
		UUID project = s.group().projectId();
		UUID lead = s.group().leader().id();
		UUID a = s.memberA().id();
		Instant afterCutoff = s.cycle().closesAt().plus(Duration.ofHours(10));
		UUID budget = s.budgets().get(2);
		UUID sub = fx.submitAndAccept(s, 2, s.cycle().closesAt().minus(Duration.ofHours(2)), afterCutoff);
		UUID settlement = fx.settlement(project, budget, s.assignments().get(2), a, sub, lead);
		UUID award = fx.ledgerAward(project, budget, settlement, a, 100_000, "award-1");

		String reversal = """
				INSERT INTO point_ledger_entries (project_id, budget_id, settlement_id, recipient_id, delta_units, entry_type,
				    reverses_entry_id, idempotency_key)
				VALUES (:p, :b, :s, :r, :d, 'REVERSAL', :rev, :k)
				""";
		// đảo sai số tiền bị chặn
		rejected(() -> jdbc.sql(reversal).param("p", project).param("b", budget).param("s", settlement).param("r", a)
				.param("d", -100_000L + 1).param("rev", award).param("k", "rev-bad").update(), "exactly");
		jdbc.sql(reversal).param("p", project).param("b", budget).param("s", settlement).param("r", a)
				.param("d", -100_000L).param("rev", award).param("k", "rev-1").update();
		// mỗi khoản chỉ đảo một lần
		rejected(() -> jdbc.sql(reversal).param("p", project).param("b", budget).param("s", settlement).param("r", a)
				.param("d", -100_000L).param("rev", award).param("k", "rev-2").update(), "point_ledger_entries");
		// thu hồi settlement (một lần, có lý do), rồi settlement mới đúng quy trình
		jdbc.sql("""
				UPDATE task_point_settlements SET revoked_at = now(), revoked_by = :by, revoke_reason = 'Duyệt nhầm' WHERE id = :id
				""").param("by", lead).param("id", settlement).update();
		rejected(() -> jdbc.sql("UPDATE task_point_settlements SET revoke_reason = 'đổi' WHERE id = :id")
				.param("id", settlement).update(), "one-time revocation");
		UUID settlement2 = fx.settlement(project, budget, s.assignments().get(2), a, sub, lead);
		fx.ledgerAward(project, budget, settlement2, a, 100_000, "award-2");
		assertThat(jdbc.sql("SELECT sum(delta_units) FROM point_ledger_entries WHERE budget_id = :b")
				.param("b", budget).query(Long.class).single()).isEqualTo(100_000L);
	}

	@Test
	void lockedPriceReservationAndPublicRequestRules() {
		PointsScenario s = fx.pointsScenarioFiveMembers();
		UUID project = s.group().projectId();
		UUID lead = s.group().leader().id();
		UUID a = s.memberA().id();
		UUID helper = s.group().members().get(1).id();
		UUID budgetHard = s.budgets().get(2);
		TaskRef taskHard = s.tasks().get(2);

		// giá P_t đã khóa không đổi
		rejected(() -> jdbc.sql("UPDATE task_point_budgets SET point_units = 1 WHERE id = :id").param("id", budgetHard).update(),
				"locked price");

		String insertHelp = """
				INSERT INTO help_requests (project_id, task_id, beneficiary_id, result_approver_id, created_by, title, description,
				    expected_output, difficulty, deadline, task_point_budget_id, advertised_point_units)
				VALUES (:p, :t, :ben, :appr, :by, 'Cần hỗ trợ', 'Mô tả công khai', 'Đầu ra', 'HARD', :dl, :b, :units)
				RETURNING id
				""";
		// giá quảng cáo phải bằng toàn bộ P_t
		rejected(() -> jdbc.sql(insertHelp).param("p", project).param("t", taskHard.taskId()).param("ben", a)
				.param("appr", lead).param("by", lead).param("dl", DbTime.toDb(taskHard.deadline())).param("b", budgetHard)
				.param("units", 50_000L).query(UUID.class).single(), "fk_help_request_budget");
		// người duyệt không được trùng người cần hỗ trợ
		rejected(() -> jdbc.sql(insertHelp).param("p", project).param("t", taskHard.taskId()).param("ben", a)
				.param("appr", a).param("by", lead).param("dl", DbTime.toDb(taskHard.deadline())).param("b", budgetHard)
				.param("units", 100_000L).query(UUID.class).single(), "help_requests_approver_chk");
		UUID help = jdbc.sql(insertHelp).param("p", project).param("t", taskHard.taskId()).param("ben", a)
				.param("appr", lead).param("by", lead).param("dl", DbTime.toDb(taskHard.deadline())).param("b", budgetHard)
				.param("units", 100_000L).query(UUID.class).single();
		// không đăng hai yêu cầu còn hiệu lực cho cùng task
		rejected(() -> jdbc.sql(insertHelp).param("p", project).param("t", taskHard.taskId()).param("ben", a)
				.param("appr", lead).param("by", lead).param("dl", DbTime.toDb(taskHard.deadline())).param("b", budgetHard)
				.param("units", 100_000L).query(UUID.class).single(), "uq_help_requests_active");

		// giữ chỗ phải đúng toàn bộ P_t, tối đa một HELD/budget
		String insertHold = "INSERT INTO task_point_reservations (budget_id, help_request_id, point_units) VALUES (:b, :h, :u)";
		rejected(() -> jdbc.sql(insertHold).param("b", budgetHard).param("h", help).param("u", 10L).update(),
				"fk_reservation_budget");
		jdbc.sql(insertHold).param("b", budgetHard).param("h", help).param("u", 100_000L).update();
		rejected(() -> jdbc.sql(insertHold).param("b", budgetHard).param("h", help).param("u", 100_000L).update(),
				"uq_task_point_reservations_");

		// người được hỗ trợ / người duyệt / TL đăng không được tự đề nghị
		String insertOffer = "INSERT INTO support_offers (project_id, help_request_id, helper_id) VALUES (:p, :h, :u)";
		rejected(() -> jdbc.sql(insertOffer).param("p", project).param("h", help).param("u", a).update(), "cannot offer");
		rejected(() -> jdbc.sql(insertOffer).param("p", project).param("h", help).param("u", lead).update(), "cannot offer");
		jdbc.sql(insertOffer).param("p", project).param("h", help).param("u", helper).update();
		rejected(() -> jdbc.sql(insertOffer).param("p", project).param("h", help).param("u", helper).update(),
				"uq_support_offers_active_per_helper");
	}

	// ------------------------------------------------------------------ thông báo, audit

	@Test
	void notificationsAreDeduplicatedAndReadOnlyExceptReadAt() {
		GroupRef g = fx.group("Notify", 3);
		UUID schedule = fx.schedule(g.projectId(), g.leader().id());
		CycleRef c1 = fx.weeklyCycles(g.projectId(), schedule, 1).get(0);
		String dedup = "checkpoint:" + c1.id() + ":reminder";
		String insertEvent = """
				INSERT INTO notification_events (project_id, event_type, entity_type, entity_id, cycle_id, dedup_key)
				VALUES (:p, 'CHECKPOINT_REMINDER', 'CHECKPOINT_CYCLE', :c, :c, :k) RETURNING id
				""";
		UUID event = jdbc.sql(insertEvent).param("p", g.projectId()).param("c", c1.id()).param("k", dedup)
				.query(UUID.class).single();
		rejected(() -> jdbc.sql(insertEvent).param("p", g.projectId()).param("c", c1.id()).param("k", dedup)
				.query(UUID.class).single(), "notification_events_dedup_key");

		String insertNote = """
				INSERT INTO notifications (event_id, project_id, recipient_id, title, message) VALUES (:e, :p, :r, 'Nhắc', 'Đến lúc cập nhật')
				""";
		UUID member = g.members().get(0).id();
		jdbc.sql(insertNote).param("e", event).param("p", g.projectId()).param("r", member).update();
		rejected(() -> jdbc.sql(insertNote).param("e", event).param("p", g.projectId()).param("r", member).update(),
				"notifications_event_recipient_key");
		rejected(() -> jdbc.sql("UPDATE notifications SET title = 'đổi' WHERE recipient_id = :r").param("r", member).update(),
				"only read_at");
		assertThat(jdbc.sql("UPDATE notifications SET read_at = now() WHERE recipient_id = :r").param("r", member).update())
				.isEqualTo(1);
	}

	@Test
	void auditLogIsAppendOnly() {
		GroupRef g = fx.group("Audit", 2);
		jdbc.sql("""
				INSERT INTO audit_logs (project_id, actor_id, entity_type, entity_id, action, after_data)
				VALUES (:p, :a, 'PROJECT', :p, 'CREATE', CAST('{"x":1}' AS jsonb))
				""").param("p", g.projectId()).param("a", g.leader().id()).update();
		rejected(() -> jdbc.sql("UPDATE audit_logs SET action = 'X' WHERE project_id = :p").param("p", g.projectId()).update(),
				"append-only");
		rejected(() -> jdbc.sql("DELETE FROM audit_logs WHERE project_id = :p").param("p", g.projectId()).update(),
				"append-only");
	}

	/** Hằng số tuần dùng trong test (tránh phụ thuộc sâu vào DbFixtures trong biểu thức dài). */
	private static final class DbFixturesWeek {
		static final Duration WEEK = Duration.ofDays(7);
	}
}
