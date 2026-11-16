-- HANARE V7: ràng buộc chéo bảng (FK vòng tròn, trigger bất biến / bảo toàn quỹ điểm) và index bổ sung.
-- Spec 12.7 (ràng buộc bắt buộc), 12.8.6-12.8.7. Điều kiện liên bảng được chặn bằng FK ghép hoặc trigger
-- (không dùng CHECK giả có subquery). Lỗi trigger dùng SQLSTATE lớp 23 (integrity_constraint_violation).

-- ===========================================================================
-- A. FK ghép bổ sung (vòng tròn giữa các file migration)
-- ===========================================================================
-- Revision hiệu lực phải là revision của CHÍNH vòng này.
ALTER TABLE task_review_rounds
    ADD CONSTRAINT fk_round_effective_plan FOREIGN KEY (id, effective_plan_id)
        REFERENCES task_plan_revisions (round_id, id);
-- Lần nộp được chấp nhận phải thuộc chính vòng này.
ALTER TABLE task_review_rounds
    ADD CONSTRAINT fk_round_accepted_submission FOREIGN KEY (id, accepted_submission_id)
        REFERENCES task_submissions (round_id, id);
-- Yêu cầu hỗ trợ công khai: quỹ phải của CHÍNH task đó và giá quảng cáo == toàn bộ P_t.
ALTER TABLE help_requests
    ADD CONSTRAINT fk_help_request_budget FOREIGN KEY (task_point_budget_id, task_id, advertised_point_units)
        REFERENCES task_point_budgets (id, task_id, point_units);

-- ===========================================================================
-- B. Chặn hard-delete các bảng cần giữ lịch sử
-- ===========================================================================
CREATE TRIGGER trg_projects_no_delete BEFORE DELETE ON projects FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_project_members_no_delete BEFORE DELETE ON project_members FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_checkpoint_cycles_no_delete BEFORE DELETE ON checkpoint_cycles FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_checkpoint_participants_no_delete BEFORE DELETE ON checkpoint_participants FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_tasks_no_delete BEFORE DELETE ON tasks FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_review_rounds_no_delete BEFORE DELETE ON task_review_rounds FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_result_reviews_no_delete BEFORE DELETE ON task_result_reviews FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_carryovers_no_delete BEFORE DELETE ON task_carryovers FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_support_requests_no_delete BEFORE DELETE ON support_requests FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_help_requests_no_delete BEFORE DELETE ON help_requests FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_support_offers_no_delete BEFORE DELETE ON support_offers FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_support_works_no_delete BEFORE DELETE ON support_works FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_contributions_no_delete BEFORE DELETE ON contributions FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_point_policies_no_delete BEFORE DELETE ON point_policies FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_member_point_budgets_no_delete BEFORE DELETE ON member_point_budgets FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_point_budgets_no_delete BEFORE DELETE ON task_point_budgets FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_point_reservations_no_delete BEFORE DELETE ON task_point_reservations FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_point_assignments_no_delete BEFORE DELETE ON task_point_assignments FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();
CREATE TRIGGER trg_task_point_settlements_no_delete BEFORE DELETE ON task_point_settlements FOR EACH ROW EXECUTE FUNCTION hanare_forbid_delete();

-- ===========================================================================
-- C. Kỳ checkpoint: ranh giới bất biến sau khi mở, trạng thái chỉ đi tiến
--    SCHEDULED -> OPEN | CLOSED | CANCELLED ; OPEN -> CLOSED ; CLOSED -> FINALIZED
--    (SCHEDULED -> CLOSED cho trường hợp job khởi động lại sau cutoff)
-- ===========================================================================
CREATE FUNCTION hanare_cycle_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'SCHEDULED' AND
       (NEW.schedule_id, NEW.sequence_no, NEW.previous_cycle_id, NEW.period_start_at, NEW.opens_at, NEW.reminder_at, NEW.closes_at)
       IS DISTINCT FROM
       (OLD.schedule_id, OLD.sequence_no, OLD.previous_cycle_id, OLD.period_start_at, OLD.opens_at, OLD.reminder_at, OLD.closes_at) THEN
        RAISE EXCEPTION 'checkpoint_cycles: schedule/boundaries of an opened or closed cycle are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'SCHEDULED' AND NEW.status IN ('OPEN', 'CLOSED', 'CANCELLED'))
        OR (OLD.status = 'OPEN' AND NEW.status = 'CLOSED')
        OR (OLD.status = 'CLOSED' AND NEW.status = 'FINALIZED')) THEN
        RAISE EXCEPTION 'checkpoint_cycles: invalid status transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkpoint_cycles_guard BEFORE UPDATE ON checkpoint_cycles
    FOR EACH ROW EXECUTE FUNCTION hanare_cycle_guard();

-- ===========================================================================
-- D. Revision kế hoạch: bản APPROVED bất biến; deadline phải nằm trong kỳ đề xuất
-- ===========================================================================
CREATE FUNCTION hanare_plan_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_start timestamptz;
    v_end   timestamptz;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'task_plan_revisions: only DRAFT revisions can be deleted'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'APPROVED' THEN
        RAISE EXCEPTION 'task_plan_revisions: an APPROVED revision is immutable (create a new revision)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status IN ('PENDING_APPROVAL', 'APPROVED') THEN
        SELECT period_start_at, closes_at INTO v_start, v_end FROM checkpoint_cycles WHERE id = NEW.proposed_cycle_id;
        -- kỳ = (period_start_at, closes_at]
        IF NOT (NEW.deadline > v_start AND NEW.deadline <= v_end) THEN
            RAISE EXCEPTION 'task_plan_revisions: deadline % is outside the cycle (%, %]', NEW.deadline, v_start, v_end
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_plan_revisions_guard BEFORE INSERT OR UPDATE OR DELETE ON task_plan_revisions
    FOR EACH ROW EXECUTE FUNCTION hanare_plan_revision_guard();

-- ===========================================================================
-- E. Vòng xử lý: effective_plan_id phải là revision APPROVED và cùng kỳ với vòng; trường nhận dạng bất biến
-- ===========================================================================
CREATE FUNCTION hanare_round_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_status varchar;
    v_cycle  uuid;
BEGIN
    IF TG_OP = 'UPDATE' AND
       (NEW.project_id, NEW.task_id, NEW.round_no, NEW.previous_round_id)
       IS DISTINCT FROM (OLD.project_id, OLD.task_id, OLD.round_no, OLD.previous_round_id) THEN
        RAISE EXCEPTION 'task_review_rounds: task/round_no/previous round are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.effective_plan_id IS NOT NULL THEN
        SELECT status, proposed_cycle_id INTO v_status, v_cycle FROM task_plan_revisions WHERE id = NEW.effective_plan_id;
        IF v_status IS DISTINCT FROM 'APPROVED' THEN
            RAISE EXCEPTION 'task_review_rounds: effective_plan_id must reference an APPROVED revision'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        IF v_cycle IS DISTINCT FROM NEW.cycle_id THEN
            RAISE EXCEPTION 'task_review_rounds: round cycle must equal the effective revision cycle'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_review_rounds_guard BEFORE INSERT OR UPDATE ON task_review_rounds
    FOR EACH ROW EXECUTE FUNCTION hanare_round_guard();

-- ===========================================================================
-- F. Snapshot / báo cáo: bất biến sau khi đóng băng / gửi
-- ===========================================================================
CREATE FUNCTION hanare_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.frozen_at IS NOT NULL THEN
        RAISE EXCEPTION 'checkpoint_task_snapshots: a frozen snapshot is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkpoint_task_snapshots_guard BEFORE UPDATE OR DELETE ON checkpoint_task_snapshots
    FOR EACH ROW EXECUTE FUNCTION hanare_snapshot_guard();

CREATE FUNCTION hanare_participant_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.frozen_at IS NOT NULL AND
       (NEW.role_at_cutoff, NEW.required_task_count, NEW.report_id_at_cutoff, NEW.cutoff_summary, NEW.frozen_at)
       IS DISTINCT FROM
       (OLD.role_at_cutoff, OLD.required_task_count, OLD.report_id_at_cutoff, OLD.cutoff_summary, OLD.frozen_at) THEN
        RAISE EXCEPTION 'checkpoint_participants: cutoff data is immutable after freezing (use final_summary)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkpoint_participants_guard BEFORE UPDATE ON checkpoint_participants
    FOR EACH ROW EXECUTE FUNCTION hanare_participant_guard();

CREATE FUNCTION hanare_report_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'SUBMITTED' THEN
        RAISE EXCEPTION 'checkpoint_reports: a SUBMITTED report is immutable (create a new revision)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkpoint_reports_guard BEFORE UPDATE OR DELETE ON checkpoint_reports
    FOR EACH ROW EXECUTE FUNCTION hanare_report_guard();

CREATE FUNCTION hanare_report_item_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_report uuid;
    v_status varchar;
BEGIN
    v_report := CASE WHEN TG_OP = 'INSERT' THEN NEW.report_id ELSE OLD.report_id END;
    SELECT status INTO v_status FROM checkpoint_reports WHERE id = v_report;
    IF v_status = 'SUBMITTED' THEN
        RAISE EXCEPTION 'checkpoint_report_items: items of a SUBMITTED report are immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_checkpoint_report_items_guard BEFORE INSERT OR UPDATE OR DELETE ON checkpoint_report_items
    FOR EACH ROW EXECUTE FUNCTION hanare_report_item_guard();

-- ===========================================================================
-- G. Hỗ trợ: Helper không được là beneficiary / người duyệt / TL đăng của chính request đó
-- ===========================================================================
CREATE FUNCTION hanare_offer_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    r help_requests%ROWTYPE;
BEGIN
    SELECT * INTO r FROM help_requests WHERE id = NEW.help_request_id;
    IF NEW.helper_id IN (r.beneficiary_id, r.result_approver_id, r.created_by) THEN
        RAISE EXCEPTION 'support_offers: beneficiary, approver and poster cannot offer on their own request'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_support_offers_guard BEFORE INSERT ON support_offers
    FOR EACH ROW EXECUTE FUNCTION hanare_offer_guard();

-- ===========================================================================
-- H. Quỹ điểm
-- ===========================================================================
-- H1. policy: tạo ở DRAFT; kích hoạt chỉ khi Σ initial = total_units và mỗi người: reserve + Σ task FUNDED = initial.
CREATE FUNCTION hanare_policy_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_sum   bigint;
    v_count int;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'point_policies: create as DRAFT, then activate after budgets are inserted'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'DRAFT' AND
       (NEW.project_id, NEW.version_no, NEW.total_units, NEW.easy_weight, NEW.medium_weight, NEW.hard_weight, NEW.member_count_at_baseline)
       IS DISTINCT FROM
       (OLD.project_id, OLD.version_no, OLD.total_units, OLD.easy_weight, OLD.medium_weight, OLD.hard_weight, OLD.member_count_at_baseline) THEN
        RAISE EXCEPTION 'point_policies: an activated policy is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE')
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'ARCHIVED')) THEN
        RAISE EXCEPTION 'point_policies: invalid status transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE' THEN
        SELECT COALESCE(SUM(initial_units), 0), COUNT(*) INTO v_sum, v_count
          FROM member_point_budgets WHERE policy_id = NEW.id;
        IF v_sum <> NEW.total_units OR v_count <> NEW.member_count_at_baseline THEN
            RAISE EXCEPTION 'point_policies: initial member budgets (% units, % members) must total % units over % members',
                v_sum, v_count, NEW.total_units, NEW.member_count_at_baseline
                USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (
            SELECT 1 FROM member_point_budgets m
             WHERE m.policy_id = NEW.id
               AND m.initial_units <> m.reserve_units + COALESCE((
                       SELECT SUM(t.point_units) FROM task_point_budgets t
                        WHERE t.origin_member_budget_id = m.id AND t.state = 'FUNDED'), 0)) THEN
            RAISE EXCEPTION 'point_policies: per member, reserve + funded task budgets must equal the initial budget'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_point_policies_guard BEFORE INSERT OR UPDATE ON point_policies
    FOR EACH ROW EXECUTE FUNCTION hanare_policy_guard();

-- H2. phần ban đầu bất biến (chỉ reserve_units được đổi, khi cấp quỹ cho task phát sinh)
CREATE FUNCTION hanare_member_budget_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.policy_id, NEW.project_id, NEW.member_id, NEW.initial_units, NEW.baseline_weight_sum)
       IS DISTINCT FROM (OLD.policy_id, OLD.project_id, OLD.member_id, OLD.initial_units, OLD.baseline_weight_sum) THEN
        RAISE EXCEPTION 'member_point_budgets: only reserve_units may change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_member_point_budgets_guard BEFORE UPDATE ON member_point_budgets
    FOR EACH ROW EXECUTE FUNCTION hanare_member_budget_guard();

-- H3. giá P_t của task đã khóa không đổi (chỉ state)
CREATE FUNCTION hanare_task_budget_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.project_id, NEW.origin_member_budget_id, NEW.task_id, NEW.difficulty_at_lock, NEW.weight_at_lock,
        NEW.point_units, NEW.locked_plan_id, NEW.locked_at)
       IS DISTINCT FROM
       (OLD.project_id, OLD.origin_member_budget_id, OLD.task_id, OLD.difficulty_at_lock, OLD.weight_at_lock,
        OLD.point_units, OLD.locked_plan_id, OLD.locked_at) THEN
        RAISE EXCEPTION 'task_point_budgets: a locked price (point_units) cannot change; only state may'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_point_budgets_guard BEFORE UPDATE ON task_point_budgets
    FOR EACH ROW EXECUTE FUNCTION hanare_task_budget_guard();

-- H4. reservation: chỉ HELD -> CONSUMED | RELEASED
CREATE FUNCTION hanare_reservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.budget_id, NEW.help_request_id, NEW.point_units, NEW.created_at)
       IS DISTINCT FROM (OLD.budget_id, OLD.help_request_id, OLD.point_units, OLD.created_at) THEN
        RAISE EXCEPTION 'task_point_reservations: only state may change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.state <> OLD.state AND NOT (OLD.state = 'HELD' AND NEW.state IN ('CONSUMED', 'RELEASED')) THEN
        RAISE EXCEPTION 'task_point_reservations: invalid state transition % -> %', OLD.state, NEW.state
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_point_reservations_guard BEFORE UPDATE ON task_point_reservations
    FOR EACH ROW EXECUTE FUNCTION hanare_reservation_guard();

-- H5. assignment: chỉ CURRENT -> SUPERSEDED (tạo assignment mới để đổi người nhận)
CREATE FUNCTION hanare_assignment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.project_id, NEW.budget_id, NEW.recipient_id, NEW.previous_assignment_id, NEW.help_request_id,
        NEW.support_work_id, NEW.agreed_at, NEW.confirmed_by)
       IS DISTINCT FROM
       (OLD.project_id, OLD.budget_id, OLD.recipient_id, OLD.previous_assignment_id, OLD.help_request_id,
        OLD.support_work_id, OLD.agreed_at, OLD.confirmed_by) THEN
        RAISE EXCEPTION 'task_point_assignments: only status may change (create a new assignment to hand over)'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'CURRENT' AND NEW.status = 'SUPERSEDED') THEN
        RAISE EXCEPTION 'task_point_assignments: invalid status transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_point_assignments_guard BEFORE UPDATE ON task_point_assignments
    FOR EACH ROW EXECUTE FUNCTION hanare_assignment_guard();

-- H6. settlement: chỉ ghi khi TL đã ACCEPTED đúng lần nộp của task; có hỗ trợ thì phải có contribution CONFIRMED.
--     Khóa dòng budget để tuần tự hóa mọi ghi điểm của cùng một task.
CREATE FUNCTION hanare_settlement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_assignment_status varchar;
    v_support_work      uuid;
    v_budget_task       uuid;
    v_submission_task   uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        -- chỉ cho thu hồi một lần: revoked_at/revoked_by/revoke_reason từ NULL -> có giá trị
        IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL OR
           (NEW.project_id, NEW.budget_id, NEW.assignment_id, NEW.recipient_id, NEW.accepted_submission_id,
            NEW.contribution_id, NEW.approved_by, NEW.approved_at)
           IS DISTINCT FROM
           (OLD.project_id, OLD.budget_id, OLD.assignment_id, OLD.recipient_id, OLD.accepted_submission_id,
            OLD.contribution_id, OLD.approved_by, OLD.approved_at) THEN
            RAISE EXCEPTION 'task_point_settlements: only a one-time revocation is allowed'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    PERFORM 1 FROM task_point_budgets WHERE id = NEW.budget_id FOR NO KEY UPDATE;

    SELECT status, support_work_id INTO v_assignment_status, v_support_work
      FROM task_point_assignments WHERE id = NEW.assignment_id;
    IF v_assignment_status IS DISTINCT FROM 'CURRENT' THEN
        RAISE EXCEPTION 'task_point_settlements: the assignment is not CURRENT'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    SELECT task_id INTO v_budget_task FROM task_point_budgets WHERE id = NEW.budget_id;
    SELECT r.task_id INTO v_submission_task
      FROM task_submissions s JOIN task_review_rounds r ON r.id = s.round_id
     WHERE s.id = NEW.accepted_submission_id;
    IF v_submission_task IS DISTINCT FROM v_budget_task THEN
        RAISE EXCEPTION 'task_point_settlements: the accepted submission does not belong to the budget task'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM task_result_reviews
                    WHERE submission_id = NEW.accepted_submission_id AND decision = 'ACCEPTED') THEN
        RAISE EXCEPTION 'task_point_settlements: the submission has not been ACCEPTED by the reviewer'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF v_support_work IS NOT NULL AND (NEW.contribution_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM contributions
             WHERE id = NEW.contribution_id AND work_id = v_support_work AND status = 'CONFIRMED')) THEN
        RAISE EXCEPTION 'task_point_settlements: a helper settlement needs a CONFIRMED contribution of the same work'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_task_point_settlements_guard BEFORE INSERT OR UPDATE ON task_point_settlements
    FOR EACH ROW EXECUTE FUNCTION hanare_settlement_guard();

-- H7. ledger: append-only; mỗi dòng đúng ±P_t; REVERSAL đúng bằng -khoản gốc; ròng mỗi budget luôn trong [0, P_t].
CREATE TRIGGER trg_point_ledger_append_only BEFORE UPDATE OR DELETE ON point_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();

CREATE FUNCTION hanare_ledger_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_units         bigint;
    v_net           bigint;
    v_orig_type     varchar;
    v_orig_delta    bigint;
    v_revoked_at    timestamptz;
BEGIN
    -- khóa budget: các lần ghi điểm cùng task được tuần tự hóa, tổng ròng kiểm tra không bị race
    SELECT point_units INTO v_units FROM task_point_budgets WHERE id = NEW.budget_id FOR NO KEY UPDATE;
    IF abs(NEW.delta_units) <> v_units THEN
        RAISE EXCEPTION 'point_ledger_entries: an entry must be exactly +/- P_t (% units), got %', v_units, NEW.delta_units
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.entry_type = 'REVERSAL' THEN
        SELECT entry_type, delta_units INTO v_orig_type, v_orig_delta FROM point_ledger_entries WHERE id = NEW.reverses_entry_id;
        IF v_orig_type IS DISTINCT FROM 'AWARD' OR v_orig_delta <> -NEW.delta_units THEN
            RAISE EXCEPTION 'point_ledger_entries: a REVERSAL must exactly negate an AWARD'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        SELECT revoked_at INTO v_revoked_at FROM task_point_settlements WHERE id = NEW.settlement_id;
        IF v_revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'point_ledger_entries: cannot AWARD on a revoked settlement'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    SELECT COALESCE(SUM(delta_units), 0) INTO v_net FROM point_ledger_entries WHERE budget_id = NEW.budget_id;
    IF v_net + NEW.delta_units < 0 OR v_net + NEW.delta_units > v_units THEN
        RAISE EXCEPTION 'point_ledger_entries: net points of a task budget must stay within [0, P_t]'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_point_ledger_guard BEFORE INSERT ON point_ledger_entries
    FOR EACH ROW EXECUTE FUNCTION hanare_ledger_guard();

-- ===========================================================================
-- I. Index bổ sung theo truy vấn thực tế (các index trong 12.7 đã khai báo cùng bảng ở V1-V6)
-- ===========================================================================
CREATE INDEX idx_task_plan_revisions_cycle ON task_plan_revisions (proposed_cycle_id) WHERE status = 'APPROVED';
CREATE INDEX idx_task_review_rounds_effective_plan ON task_review_rounds (effective_plan_id) WHERE effective_plan_id IS NOT NULL;
CREATE INDEX idx_task_submissions_plan ON task_submissions (plan_revision_id);
CREATE INDEX idx_checkpoint_task_snapshots_task ON checkpoint_task_snapshots (task_id);
CREATE INDEX idx_support_requests_task ON support_requests (task_id);
CREATE INDEX idx_help_requests_beneficiary ON help_requests (beneficiary_id, status);
CREATE INDEX idx_task_point_settlements_recipient ON task_point_settlements (project_id, recipient_id) WHERE revoked_at IS NULL;
