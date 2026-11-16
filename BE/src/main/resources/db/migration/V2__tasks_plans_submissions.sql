-- HANARE V2: task, vòng xử lý, revision kế hoạch, lần nộp, quyết định TL, chuyển kỳ, audit.
-- Spec 12.4 + 6.1. Trạng thái tổng của task KHÔNG lưu: BE dựng từ vòng hiện tại + lần nộp + quyết định.
-- Các FK vòng tròn (effective_plan_id, accepted_submission_id) được thêm ở V7.

-- ---------------------------------------------------------------------------
-- tasks: định danh ổn định xuyên các kỳ
-- ---------------------------------------------------------------------------
CREATE TABLE tasks (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id                  uuid NOT NULL REFERENCES projects (id),
    created_by                  uuid NOT NULL,
    -- 12.8.7.6: người duyệt độc lập cho task thường của TL (chỉ định trước, có audit)
    independent_approver_id     uuid,
    independent_approver_set_by uuid,
    independent_approver_set_at timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    archived_at                 timestamptz,
    version                     bigint NOT NULL DEFAULT 0,
    CONSTRAINT tasks_project_id_key UNIQUE (project_id, id),
    CONSTRAINT tasks_indep_approver_chk CHECK (
        (independent_approver_id IS NULL) = (independent_approver_set_at IS NULL)
        AND (independent_approver_id IS NULL) = (independent_approver_set_by IS NULL)),
    CONSTRAINT fk_task_creator FOREIGN KEY (project_id, created_by)
        REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_task_indep_approver FOREIGN KEY (project_id, independent_approver_id)
        REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_task_indep_approver_by FOREIGN KEY (project_id, independent_approver_set_by)
        REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_tasks_project ON tasks (project_id) WHERE archived_at IS NULL;

-- ---------------------------------------------------------------------------
-- task_review_rounds: vòng xử lý (vòng đầu + các vòng chuyển kỳ)
-- ---------------------------------------------------------------------------
CREATE TABLE task_review_rounds (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id             uuid NOT NULL,
    task_id                uuid NOT NULL,
    round_no               int NOT NULL,
    cycle_id               uuid NOT NULL,                -- kỳ hiện hành của vòng (đổi khi TL duyệt revision đổi kỳ)
    previous_round_id      uuid,
    reason                 varchar(20) NOT NULL,
    state                  varchar(24) NOT NULL DEFAULT 'DRAFT',
    effective_plan_id      uuid,                         -- revision APPROVED của chính vòng này (FK ghép ở V7)
    accepted_submission_id uuid,                         -- lần nộp được TL chấp nhận (FK ghép ở V7)
    completed_at           timestamptz,                  -- = submitted_at của lần nộp được chấp nhận
    accepted_at            timestamptz,                  -- = reviewed_at của TL (không dùng để phạt người nộp)
    started_at             timestamptz,
    created_by             uuid NOT NULL,
    closed_at              timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0,
    CONSTRAINT task_review_rounds_task_round_key UNIQUE (task_id, round_no),
    CONSTRAINT task_review_rounds_project_id_key UNIQUE (project_id, id),
    CONSTRAINT task_review_rounds_task_id_key UNIQUE (task_id, id),
    CONSTRAINT task_review_rounds_no_pos CHECK (round_no >= 1),
    CONSTRAINT task_review_rounds_reason_chk CHECK (reason IN ('INITIAL', 'NOT_SUBMITTED', 'FIX_REQUIRED', 'UPDATE_REQUIRED')),
    CONSTRAINT task_review_rounds_state_chk CHECK (state IN
        ('DRAFT', 'ACTIVE', 'AWAITING_REVIEW', 'ACCEPTED', 'CARRYOVER_REQUIRED', 'CARRIED_OVER')),
    CONSTRAINT task_review_rounds_first_chk CHECK ((round_no = 1) = (previous_round_id IS NULL)),
    CONSTRAINT task_review_rounds_initial_chk CHECK ((round_no = 1) = (reason = 'INITIAL')),
    CONSTRAINT task_review_rounds_plan_chk CHECK (state = 'DRAFT' OR effective_plan_id IS NOT NULL),
    CONSTRAINT task_review_rounds_closed_chk CHECK ((state IN ('ACCEPTED', 'CARRIED_OVER')) = (closed_at IS NOT NULL)),
    CONSTRAINT task_review_rounds_accepted_chk CHECK (
        (state = 'ACCEPTED') = (accepted_submission_id IS NOT NULL AND completed_at IS NOT NULL AND accepted_at IS NOT NULL)
        AND (state = 'ACCEPTED' OR (accepted_submission_id IS NULL AND completed_at IS NULL AND accepted_at IS NULL))),
    CONSTRAINT fk_round_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_round_cycle FOREIGN KEY (project_id, cycle_id) REFERENCES checkpoint_cycles (project_id, id),
    CONSTRAINT fk_round_previous FOREIGN KEY (task_id, previous_round_id) REFERENCES task_review_rounds (task_id, id),
    CONSTRAINT fk_round_creator FOREIGN KEY (project_id, created_by) REFERENCES project_members (project_id, user_id)
);
-- previous_round_id duy nhất: không tạo hai vòng tiếp theo cho cùng vòng cũ
CREATE UNIQUE INDEX uq_task_review_rounds_previous
    ON task_review_rounds (previous_round_id) WHERE previous_round_id IS NOT NULL;
-- tối đa một vòng chưa kết thúc/task (khi chuyển kỳ: đóng nguồn CARRIED_OVER trước, rồi tạo vòng đích)
CREATE UNIQUE INDEX uq_task_review_rounds_one_open
    ON task_review_rounds (task_id) WHERE state NOT IN ('ACCEPTED', 'CARRIED_OVER');
CREATE INDEX idx_task_review_rounds_cycle_state ON task_review_rounds (project_id, cycle_id, state);

-- ---------------------------------------------------------------------------
-- task_plan_revisions: phiên bản kế hoạch (nháp -> chờ duyệt -> được duyệt/trả lại)
-- ---------------------------------------------------------------------------
CREATE TABLE task_plan_revisions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid NOT NULL,
    round_id          uuid NOT NULL,
    revision_no       int NOT NULL,
    base_revision_id  uuid,
    proposed_cycle_id uuid,
    assignee_id       uuid,
    title             varchar(200),
    description       text,                              -- phạm vi
    expected_output   text,                              -- đầu ra
    start_at          timestamptz,
    deadline          timestamptz,
    difficulty        varchar(8),
    status            varchar(20) NOT NULL DEFAULT 'DRAFT',
    created_by        uuid NOT NULL,
    submitted_by      uuid,
    submitted_at      timestamptz,
    reviewed_by       uuid,
    reviewed_at       timestamptz,
    review_note       text,
    approval_source   varchar(24),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT task_plan_revisions_round_rev_key UNIQUE (round_id, revision_no),
    CONSTRAINT task_plan_revisions_round_id_key UNIQUE (round_id, id),
    CONSTRAINT task_plan_revisions_project_id_key UNIQUE (project_id, id),
    CONSTRAINT task_plan_revisions_no_pos CHECK (revision_no >= 1),
    CONSTRAINT task_plan_revisions_status_chk CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'CHANGES_REQUESTED', 'APPROVED')),
    CONSTRAINT task_plan_revisions_diff_chk CHECK (difficulty IS NULL OR difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT task_plan_revisions_source_chk CHECK (approval_source IS NULL OR approval_source IN
        ('MEMBER_PLAN', 'TL_CARRYOVER', 'TL_SUPPORT_HANDOFF')),
    CONSTRAINT task_plan_revisions_time_chk CHECK (start_at IS NULL OR deadline IS NULL OR start_at <= deadline),
    -- PENDING_APPROVAL/APPROVED bắt buộc đủ người phụ trách, kỳ, phạm vi, đầu ra, ngày/giờ, độ khó
    CONSTRAINT task_plan_revisions_complete_chk CHECK (status IN ('DRAFT', 'CHANGES_REQUESTED') OR (
        assignee_id IS NOT NULL AND proposed_cycle_id IS NOT NULL
        AND title IS NOT NULL AND btrim(title) <> ''
        AND description IS NOT NULL AND btrim(description) <> ''
        AND expected_output IS NOT NULL AND btrim(expected_output) <> ''
        AND start_at IS NOT NULL AND deadline IS NOT NULL AND difficulty IS NOT NULL
        AND submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT task_plan_revisions_approved_chk CHECK (status <> 'APPROVED' OR (
        reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND approval_source IS NOT NULL)),
    CONSTRAINT task_plan_revisions_changes_chk CHECK (status <> 'CHANGES_REQUESTED' OR (
        review_note IS NOT NULL AND btrim(review_note) <> '' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT fk_revision_round FOREIGN KEY (project_id, round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_revision_base FOREIGN KEY (round_id, base_revision_id) REFERENCES task_plan_revisions (round_id, id),
    CONSTRAINT fk_revision_cycle FOREIGN KEY (project_id, proposed_cycle_id) REFERENCES checkpoint_cycles (project_id, id),
    CONSTRAINT fk_revision_assignee FOREIGN KEY (project_id, assignee_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_revision_creator FOREIGN KEY (project_id, created_by) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_revision_submitter FOREIGN KEY (project_id, submitted_by) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_revision_reviewer FOREIGN KEY (project_id, reviewed_by) REFERENCES project_members (project_id, user_id)
);
-- tối đa một revision đang chỉnh/chờ duyệt (DRAFT / PENDING_APPROVAL / CHANGES_REQUESTED) mỗi vòng
CREATE UNIQUE INDEX uq_task_plan_revisions_one_open
    ON task_plan_revisions (round_id) WHERE status <> 'APPROVED';
CREATE INDEX idx_task_plan_revisions_round_status ON task_plan_revisions (round_id, status);
CREATE INDEX idx_task_plan_revisions_assignee_deadline
    ON task_plan_revisions (assignee_id, deadline) WHERE status = 'APPROVED';

-- ---------------------------------------------------------------------------
-- task_submissions: lần nộp minh chứng (bất biến sau khi gửi)
-- evidence = JSON array, mỗi phần tử {"type":"URL"|"FILE","url":"...","label":"..."}; >= 1 phần tử.
-- submitted_at do BE/DB cấp (qua Clock), không nhận từ FE. Đúng hạn khi submitted_at <= deadline.
-- ---------------------------------------------------------------------------
CREATE TABLE task_submissions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id       uuid NOT NULL,
    round_id         uuid NOT NULL,
    plan_revision_id uuid NOT NULL,
    submission_no    int NOT NULL,
    submitted_by     uuid NOT NULL,
    result_note      text,
    evidence         jsonb NOT NULL,
    submitted_at     timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_submissions_round_no_key UNIQUE (round_id, submission_no),
    CONSTRAINT task_submissions_round_id_key UNIQUE (round_id, id),
    CONSTRAINT task_submissions_project_id_key UNIQUE (project_id, id),
    CONSTRAINT task_submissions_no_pos CHECK (submission_no >= 1),
    CONSTRAINT task_submissions_evidence_chk CHECK (jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) >= 1),
    CONSTRAINT fk_submission_round FOREIGN KEY (project_id, round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_submission_plan FOREIGN KEY (round_id, plan_revision_id) REFERENCES task_plan_revisions (round_id, id),
    CONSTRAINT fk_submission_submitter FOREIGN KEY (project_id, submitted_by) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_task_submissions_round_time ON task_submissions (round_id, submitted_at DESC);
CREATE TRIGGER trg_task_submissions_immutable
    BEFORE UPDATE OR DELETE ON task_submissions
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();

-- ---------------------------------------------------------------------------
-- task_result_reviews: quyết định của TL (hoặc người duyệt độc lập) trên MỘT lần nộp
-- Chỉnh quyết định đã ghi = UPDATE có audit_logs kèm theo (service).
-- ---------------------------------------------------------------------------
CREATE TABLE task_result_reviews (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid NOT NULL,
    submission_id     uuid NOT NULL,
    decision          varchar(16) NOT NULL,
    reviewer_id       uuid NOT NULL,
    issue_description text,
    reviewed_at       timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_result_reviews_submission_key UNIQUE (submission_id),
    CONSTRAINT task_result_reviews_project_id_key UNIQUE (project_id, id),
    CONSTRAINT task_result_reviews_decision_chk CHECK (decision IN ('ACCEPTED', 'FIX_REQUIRED', 'UPDATE_REQUIRED')),
    CONSTRAINT task_result_reviews_issue_chk CHECK (
        decision = 'ACCEPTED' OR (issue_description IS NOT NULL AND btrim(issue_description) <> '')),
    CONSTRAINT fk_review_submission FOREIGN KEY (project_id, submission_id) REFERENCES task_submissions (project_id, id),
    CONSTRAINT fk_review_reviewer FOREIGN KEY (project_id, reviewer_id) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_task_result_reviews_reviewer ON task_result_reviews (project_id, reviewed_at DESC);

-- ---------------------------------------------------------------------------
-- task_carryovers: chuyển kỳ do TL chốt (nguồn -> đích, cùng task)
-- ---------------------------------------------------------------------------
CREATE TABLE task_carryovers (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id       uuid NOT NULL,
    source_round_id  uuid NOT NULL,
    target_round_id  uuid NOT NULL,
    source_review_id uuid,                               -- quyết định FIX/UPDATE nguồn; NULL nếu nguồn "chưa nộp"
    decided_by       uuid NOT NULL,
    decision_reason  text NOT NULL,
    decided_at       timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_carryovers_source_key UNIQUE (source_round_id),
    CONSTRAINT task_carryovers_target_key UNIQUE (target_round_id),
    CONSTRAINT task_carryovers_diff_chk CHECK (source_round_id <> target_round_id),
    CONSTRAINT task_carryovers_reason_chk CHECK (btrim(decision_reason) <> ''),
    CONSTRAINT fk_carryover_source FOREIGN KEY (project_id, source_round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_carryover_target FOREIGN KEY (project_id, target_round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_carryover_review FOREIGN KEY (project_id, source_review_id) REFERENCES task_result_reviews (project_id, id),
    CONSTRAINT fk_carryover_decider FOREIGN KEY (project_id, decided_by) REFERENCES project_members (project_id, user_id)
);

-- ---------------------------------------------------------------------------
-- audit_logs: append-only. Không ghi mật khẩu/token.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     uuid NOT NULL REFERENCES projects (id),
    actor_id       uuid REFERENCES users (id),            -- NULL = hệ thống/job
    entity_type    varchar(60) NOT NULL,
    entity_id      uuid NOT NULL,
    action         varchar(60) NOT NULL,
    before_data    jsonb,
    after_data     jsonb,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    correlation_id varchar(64)
);
CREATE INDEX idx_audit_logs_entity ON audit_logs (project_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_id, occurred_at DESC);
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();
