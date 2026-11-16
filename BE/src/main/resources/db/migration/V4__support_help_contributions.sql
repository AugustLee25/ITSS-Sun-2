-- HANARE V4: hỗ trợ riêng (Member -> TL), yêu cầu hỗ trợ công khai (TL đăng), offer, việc hỗ trợ,
-- lần nộp hỗ trợ, đóng góp, báo nguy cơ. Spec 12.6 + 6.2.
-- help_requests.task_point_budget_id -> task_point_budgets: FK ghép thêm ở V7 (bảng điểm tạo ở V6).

-- ---------------------------------------------------------------------------
-- support_requests: đề nghị RIÊNG Member -> TL (nội dung chỉ người gửi + TL xem)
-- ---------------------------------------------------------------------------
CREATE TABLE support_requests (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid NOT NULL,
    task_id       uuid NOT NULL,
    round_id      uuid,
    requester_id  uuid NOT NULL,
    obstacle_type varchar(40) NOT NULL,                 -- loại trở ngại (S07)
    description   text NOT NULL,
    help_needed   text,                                 -- phần cần hỗ trợ
    impact        text,
    expected_at   timestamptz,                          -- ngày dự kiến nếu biết (không tự đổi deadline)
    status        varchar(12) NOT NULL DEFAULT 'OPEN',
    decision      varchar(20),
    lead_note     text,                                 -- ghi chú riêng của TL
    reviewed_by   uuid,
    reviewed_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint NOT NULL DEFAULT 0,
    CONSTRAINT support_requests_project_id_key UNIQUE (project_id, id),
    CONSTRAINT support_requests_status_chk CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED')),
    CONSTRAINT support_requests_decision_chk CHECK (decision IS NULL OR decision IN ('RESOLVED_PRIVATELY', 'HELP_PUBLISHED')),
    CONSTRAINT support_requests_resolved_chk CHECK ((status = 'RESOLVED') = (decision IS NOT NULL)),
    CONSTRAINT support_requests_review_chk CHECK (status = 'OPEN' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)),
    CONSTRAINT fk_support_request_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_support_request_round FOREIGN KEY (project_id, round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_support_request_requester FOREIGN KEY (project_id, requester_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_support_request_reviewer FOREIGN KEY (project_id, reviewed_by) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_support_requests_status ON support_requests (project_id, status, created_at);
CREATE INDEX idx_support_requests_requester ON support_requests (requester_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- help_requests: phần việc CÔNG KHAI do TL đăng (toàn bộ task chưa được chấp nhận)
-- ---------------------------------------------------------------------------
CREATE TABLE help_requests (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id             uuid NOT NULL,
    support_request_id     uuid,                        -- liên kết đề nghị riêng nguồn (không lộ nội dung riêng)
    task_id                uuid NOT NULL,
    round_id               uuid,
    beneficiary_id         uuid NOT NULL,               -- người đang cần hỗ trợ (= người gốc)
    result_approver_id     uuid NOT NULL,               -- người duyệt kết quả/điểm (TL, hoặc người duyệt độc lập)
    created_by             uuid NOT NULL,               -- TL đăng
    title                  varchar(200) NOT NULL,
    description            text NOT NULL,               -- chỉ nội dung công khai
    expected_output        text NOT NULL,
    difficulty             varchar(8) NOT NULL,
    deadline               timestamptz NOT NULL,
    effort_estimate_minutes int,                        -- chỉ tham khảo
    skill_note             text,
    task_point_budget_id   uuid,                        -- quỹ task (FK ghép ở V7); NULL = chưa có phương án điểm
    advertised_point_units bigint,                       -- = toàn bộ point_units của quỹ task lúc đăng (BE điền)
    status                 varchar(12) NOT NULL DEFAULT 'OPEN',
    cancelled_at           timestamptz,
    cancel_reason          text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                bigint NOT NULL DEFAULT 0,
    CONSTRAINT help_requests_project_id_key UNIQUE (project_id, id),
    CONSTRAINT help_requests_status_chk CHECK (status IN ('OPEN', 'MATCHED', 'IN_PROGRESS', 'SUBMITTED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT help_requests_diff_chk CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT help_requests_effort_chk CHECK (effort_estimate_minutes IS NULL OR effort_estimate_minutes > 0),
    CONSTRAINT help_requests_points_chk CHECK (
        (task_point_budget_id IS NULL) = (advertised_point_units IS NULL)
        AND (advertised_point_units IS NULL OR advertised_point_units > 0)),
    CONSTRAINT help_requests_cancel_chk CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    -- không ai tự duyệt kết quả/điểm của chính mình
    CONSTRAINT help_requests_approver_chk CHECK (result_approver_id <> beneficiary_id),
    CONSTRAINT fk_help_request_support FOREIGN KEY (project_id, support_request_id) REFERENCES support_requests (project_id, id),
    CONSTRAINT fk_help_request_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_help_request_round FOREIGN KEY (project_id, round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_help_request_beneficiary FOREIGN KEY (project_id, beneficiary_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_help_request_approver FOREIGN KEY (project_id, result_approver_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_help_request_creator FOREIGN KEY (project_id, created_by) REFERENCES project_members (project_id, user_id)
);
-- không đăng hai yêu cầu còn hiệu lực cho cùng task (COMPLETED vẫn chiếm chỗ: task đã bàn giao không đăng lại)
CREATE UNIQUE INDEX uq_help_requests_active_task ON help_requests (task_id) WHERE status <> 'CANCELLED';
CREATE UNIQUE INDEX uq_help_requests_active_budget
    ON help_requests (task_point_budget_id) WHERE task_point_budget_id IS NOT NULL AND status <> 'CANCELLED';
CREATE INDEX idx_help_requests_status_deadline ON help_requests (project_id, status, deadline);

-- ---------------------------------------------------------------------------
-- support_offers: Helper tự nguyện đề nghị
-- ---------------------------------------------------------------------------
CREATE TABLE support_offers (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      uuid NOT NULL,
    help_request_id uuid NOT NULL,
    helper_id       uuid NOT NULL,
    message         text,
    status          varchar(12) NOT NULL DEFAULT 'PENDING',
    created_at      timestamptz NOT NULL DEFAULT now(),
    decided_at      timestamptz,
    decided_by      uuid,
    CONSTRAINT support_offers_project_id_key UNIQUE (project_id, id),
    CONSTRAINT support_offers_work_ref_key UNIQUE (help_request_id, id, helper_id),
    CONSTRAINT support_offers_status_chk CHECK (status IN ('PENDING', 'ACCEPTED', 'NOT_SELECTED', 'WITHDRAWN')),
    CONSTRAINT support_offers_decided_chk CHECK (
        (status IN ('ACCEPTED', 'NOT_SELECTED')) = (decided_at IS NOT NULL AND decided_by IS NOT NULL)),
    CONSTRAINT fk_offer_request FOREIGN KEY (project_id, help_request_id) REFERENCES help_requests (project_id, id),
    CONSTRAINT fk_offer_helper FOREIGN KEY (project_id, helper_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_offer_decider FOREIGN KEY (project_id, decided_by) REFERENCES project_members (project_id, user_id)
);
-- một offer đang hoạt động/người/request; tối đa một ACCEPTED/request
CREATE UNIQUE INDEX uq_support_offers_active_per_helper
    ON support_offers (help_request_id, helper_id) WHERE status IN ('PENDING', 'ACCEPTED');
CREATE UNIQUE INDEX uq_support_offers_one_accepted ON support_offers (help_request_id) WHERE status = 'ACCEPTED';
CREATE INDEX idx_support_offers_helper ON support_offers (helper_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- support_works: phần việc của Helper đã được chọn (MVP: một người/phần việc)
-- ---------------------------------------------------------------------------
CREATE TABLE support_works (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id          uuid NOT NULL,
    help_request_id     uuid NOT NULL,
    accepted_offer_id   uuid NOT NULL,
    helper_id           uuid NOT NULL,
    result_approver_id  uuid NOT NULL,
    scope_snapshot      text NOT NULL,                  -- phạm vi đã chấp thuận
    deadline_snapshot   timestamptz NOT NULL,
    difficulty_snapshot varchar(8) NOT NULL,
    point_units_snapshot bigint,                         -- P_t đã chấp thuận (NULL nếu không có điểm)
    status              varchar(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint NOT NULL DEFAULT 0,
    CONSTRAINT support_works_request_key UNIQUE (help_request_id),
    CONSTRAINT support_works_offer_key UNIQUE (accepted_offer_id),
    CONSTRAINT support_works_project_id_key UNIQUE (project_id, id),
    CONSTRAINT support_works_status_chk CHECK (status IN
        ('IN_PROGRESS', 'SUBMITTED', 'REVISION_REQUESTED', 'NEEDS_DISCUSSION', 'APPROVED')),
    CONSTRAINT support_works_diff_chk CHECK (difficulty_snapshot IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT support_works_points_chk CHECK (point_units_snapshot IS NULL OR point_units_snapshot > 0),
    -- Helper không phải người duyệt kết quả của chính mình
    CONSTRAINT support_works_helper_chk CHECK (helper_id <> result_approver_id),
    CONSTRAINT fk_work_request FOREIGN KEY (project_id, help_request_id) REFERENCES help_requests (project_id, id),
    -- offer phải thuộc đúng request và đúng helper
    CONSTRAINT fk_work_offer FOREIGN KEY (help_request_id, accepted_offer_id, helper_id)
        REFERENCES support_offers (help_request_id, id, helper_id),
    CONSTRAINT fk_work_helper FOREIGN KEY (project_id, helper_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_work_approver FOREIGN KEY (project_id, result_approver_id) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_support_works_helper ON support_works (helper_id, status);

-- ---------------------------------------------------------------------------
-- support_submissions: các phiên bản bàn giao của Helper (bất biến)
-- evidence = JSON array như task_submissions.evidence
-- ---------------------------------------------------------------------------
CREATE TABLE support_submissions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    work_id        uuid NOT NULL REFERENCES support_works (id),
    revision_no    int NOT NULL,
    result_note    text NOT NULL,
    evidence       jsonb NOT NULL,
    actual_minutes int,                                  -- tự khai, chỉ ghi nhận công sức; không quy đổi điểm
    submitted_at   timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT support_submissions_work_rev_key UNIQUE (work_id, revision_no),
    CONSTRAINT support_submissions_work_id_key UNIQUE (work_id, id),
    CONSTRAINT support_submissions_rev_pos CHECK (revision_no >= 1),
    CONSTRAINT support_submissions_note_chk CHECK (btrim(result_note) <> ''),
    CONSTRAINT support_submissions_evidence_chk CHECK (jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) >= 1),
    CONSTRAINT support_submissions_minutes_chk CHECK (actual_minutes IS NULL OR actual_minutes > 0)
);
CREATE TRIGGER trg_support_submissions_immutable
    BEFORE UPDATE OR DELETE ON support_submissions
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();

-- ---------------------------------------------------------------------------
-- contributions: một bản ghi chính thức/work; CONFIRMED chỉ sau người duyệt độc lập xác nhận
-- ---------------------------------------------------------------------------
CREATE TABLE contributions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id              uuid NOT NULL,
    work_id                 uuid NOT NULL,
    help_request_id         uuid NOT NULL,
    helper_id               uuid NOT NULL,
    accepted_submission_id  uuid,
    status                  varchar(24) NOT NULL DEFAULT 'PENDING_CONFIRMATION',
    confirmed_by            uuid,
    confirmed_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT contributions_work_key UNIQUE (work_id),
    CONSTRAINT contributions_project_id_key UNIQUE (project_id, id),
    CONSTRAINT contributions_work_ref_key UNIQUE (work_id, id),
    CONSTRAINT contributions_status_chk CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED')),
    CONSTRAINT contributions_confirmed_chk CHECK (status <> 'CONFIRMED' OR (
        confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND accepted_submission_id IS NOT NULL)),
    CONSTRAINT contributions_not_self_chk CHECK (confirmed_by IS NULL OR confirmed_by <> helper_id),
    CONSTRAINT fk_contribution_work FOREIGN KEY (project_id, work_id) REFERENCES support_works (project_id, id),
    CONSTRAINT fk_contribution_request FOREIGN KEY (project_id, help_request_id) REFERENCES help_requests (project_id, id),
    CONSTRAINT fk_contribution_submission FOREIGN KEY (work_id, accepted_submission_id)
        REFERENCES support_submissions (work_id, id),
    CONSTRAINT fk_contribution_helper FOREIGN KEY (project_id, helper_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_contribution_confirmer FOREIGN KEY (project_id, confirmed_by) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_contributions_helper ON contributions (helper_id, status);

-- ---------------------------------------------------------------------------
-- risk_reports: nguy cơ (DROPOUT_RISK chỉ có hiệu lực tô đỏ khi TL xác nhận). Spec 12.6.
-- Chỉ chứa public_summary; ghi chú riêng nằm ở support_requests / audit có quyền riêng.
-- ---------------------------------------------------------------------------
CREATE TABLE risk_reports (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid NOT NULL,
    task_id           uuid,
    subject_member_id uuid NOT NULL,
    reported_by       uuid NOT NULL,
    risk_type         varchar(16) NOT NULL,
    public_summary    text NOT NULL,
    status            varchar(12) NOT NULL DEFAULT 'OPEN',
    confirmed_by      uuid,
    confirmed_at      timestamptz,
    resolved_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT risk_reports_project_id_key UNIQUE (project_id, id),
    CONSTRAINT risk_reports_type_chk CHECK (risk_type IN ('DELAY_RISK', 'BLOCKED', 'DROPOUT_RISK')),
    CONSTRAINT risk_reports_status_chk CHECK (status IN ('OPEN', 'CONFIRMED', 'RESOLVED')),
    CONSTRAINT risk_reports_confirmed_chk CHECK (status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT risk_reports_resolved_chk CHECK (status <> 'RESOLVED' OR resolved_at IS NOT NULL),
    CONSTRAINT fk_risk_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_risk_subject FOREIGN KEY (project_id, subject_member_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_risk_reporter FOREIGN KEY (project_id, reported_by) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_risk_confirmer FOREIGN KEY (project_id, confirmed_by) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_risk_reports_subject ON risk_reports (project_id, subject_member_id, status);
