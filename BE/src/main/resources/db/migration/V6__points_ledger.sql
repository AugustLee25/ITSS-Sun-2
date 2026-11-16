-- HANARE V6: quỹ điểm toàn dự án, định giá task, giữ chỗ, phân quyền nhận, settlement, sổ điểm. Spec 12.8.
-- Đơn vị: point_units, 1 điểm % = 10.000 units, tổng quỹ dự án 1.000.000 units = 100%.
-- Lưu bigint point_units (số nguyên) để tổng luôn khớp; chỉ định dạng 4 chữ số thập phân khi hiển thị.
-- Các trigger bất biến/kiểm tra tổng ở V7.

-- ---------------------------------------------------------------------------
-- point_policies: phiên bản phương án điểm (không cấp thêm quỹ mỗi phiên bản)
-- ---------------------------------------------------------------------------
CREATE TABLE point_policies (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id                uuid NOT NULL REFERENCES projects (id),
    version_no                int NOT NULL,
    total_units               bigint NOT NULL DEFAULT 1000000,
    easy_weight               int NOT NULL DEFAULT 1,
    medium_weight             int NOT NULL DEFAULT 2,
    hard_weight               int NOT NULL DEFAULT 3,
    member_count_at_baseline  int NOT NULL,
    status                    varchar(10) NOT NULL DEFAULT 'DRAFT',
    created_by                uuid NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    approved_by               uuid,
    approved_at               timestamptz,
    CONSTRAINT point_policies_version_key UNIQUE (project_id, version_no),
    CONSTRAINT point_policies_project_id_key UNIQUE (project_id, id),
    CONSTRAINT point_policies_total_chk CHECK (total_units = 1000000),
    CONSTRAINT point_policies_weights_chk CHECK (easy_weight > 0 AND medium_weight > 0 AND hard_weight > 0),
    CONSTRAINT point_policies_members_chk CHECK (member_count_at_baseline > 0),
    CONSTRAINT point_policies_status_chk CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT point_policies_approved_chk CHECK (status = 'DRAFT' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT fk_policy_creator FOREIGN KEY (project_id, created_by) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_policy_approver FOREIGN KEY (project_id, approved_by) REFERENCES project_members (project_id, user_id)
);
-- một policy đang áp dụng/project
CREATE UNIQUE INDEX uq_point_policies_one_active ON point_policies (project_id) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- member_point_budgets: phần ban đầu 100/N của từng người + phần dự phòng
-- ---------------------------------------------------------------------------
CREATE TABLE member_point_budgets (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id           uuid NOT NULL,
    project_id          uuid NOT NULL,
    member_id           uuid NOT NULL,
    initial_units       bigint NOT NULL,
    reserve_units       bigint NOT NULL DEFAULT 0,
    baseline_weight_sum int NOT NULL DEFAULT 0,          -- W_i
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT member_point_budgets_policy_member_key UNIQUE (policy_id, member_id),
    CONSTRAINT member_point_budgets_project_id_key UNIQUE (project_id, id),
    CONSTRAINT member_point_budgets_units_chk CHECK (initial_units >= 0 AND reserve_units >= 0 AND reserve_units <= initial_units),
    CONSTRAINT member_point_budgets_weight_chk CHECK (baseline_weight_sum >= 0),
    CONSTRAINT fk_member_budget_policy FOREIGN KEY (project_id, policy_id) REFERENCES point_policies (project_id, id),
    CONSTRAINT fk_member_budget_member FOREIGN KEY (project_id, member_id) REFERENCES project_members (project_id, user_id)
);

-- ---------------------------------------------------------------------------
-- task_point_budgets: quỹ duy nhất của task (giá P_t khóa, xuyên mọi vòng sửa/chuyển kỳ)
-- ---------------------------------------------------------------------------
CREATE TABLE task_point_budgets (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id               uuid NOT NULL,
    origin_member_budget_id  uuid NOT NULL,              -- người gốc của quỹ
    task_id                  uuid NOT NULL,
    difficulty_at_lock       varchar(8) NOT NULL,
    weight_at_lock           int NOT NULL,
    point_units              bigint NOT NULL,
    locked_plan_id           uuid NOT NULL REFERENCES task_plan_revisions (id),
    locked_at                timestamptz NOT NULL DEFAULT now(),
    state                    varchar(8) NOT NULL DEFAULT 'FUNDED',
    created_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_point_budgets_task_key UNIQUE (task_id),
    CONSTRAINT task_point_budgets_project_id_key UNIQUE (project_id, id),
    CONSTRAINT task_point_budgets_price_ref_key UNIQUE (id, point_units),
    CONSTRAINT task_point_budgets_help_ref_key UNIQUE (id, task_id, point_units),
    CONSTRAINT task_point_budgets_diff_chk CHECK (difficulty_at_lock IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT task_point_budgets_weight_chk CHECK (weight_at_lock > 0),
    CONSTRAINT task_point_budgets_units_chk CHECK (point_units > 0 AND point_units <= 1000000),
    CONSTRAINT task_point_budgets_state_chk CHECK (state IN ('FUNDED', 'RETIRED')),
    CONSTRAINT fk_task_budget_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_task_budget_origin FOREIGN KEY (project_id, origin_member_budget_id)
        REFERENCES member_point_budgets (project_id, id)
);
CREATE INDEX idx_task_point_budgets_origin ON task_point_budgets (origin_member_budget_id);

-- ---------------------------------------------------------------------------
-- task_point_reservations: giữ TOÀN BỘ quỹ task cho một help_request (không phải bút toán điểm)
-- (budget_id, point_units) -> task_point_budgets: luôn bằng đúng P_t.
-- ---------------------------------------------------------------------------
CREATE TABLE task_point_reservations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    budget_id       uuid NOT NULL,
    help_request_id uuid NOT NULL REFERENCES help_requests (id),
    point_units     bigint NOT NULL,
    state           varchar(10) NOT NULL DEFAULT 'HELD',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_point_reservations_state_chk CHECK (state IN ('HELD', 'CONSUMED', 'RELEASED')),
    CONSTRAINT fk_reservation_budget FOREIGN KEY (budget_id, point_units) REFERENCES task_point_budgets (id, point_units)
);
CREATE UNIQUE INDEX uq_task_point_reservations_one_held ON task_point_reservations (budget_id) WHERE state = 'HELD';
CREATE UNIQUE INDEX uq_task_point_reservations_request ON task_point_reservations (help_request_id) WHERE state = 'HELD';
CREATE INDEX idx_task_point_reservations_budget ON task_point_reservations (budget_id);

-- ---------------------------------------------------------------------------
-- task_point_assignments: ai đang có quyền nhận 100% P_t (đúng một CURRENT/budget)
-- ---------------------------------------------------------------------------
CREATE TABLE task_point_assignments (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id             uuid NOT NULL,
    budget_id              uuid NOT NULL,
    recipient_id           uuid NOT NULL,
    previous_assignment_id uuid,
    help_request_id        uuid REFERENCES help_requests (id),
    support_work_id        uuid REFERENCES support_works (id),
    status                 varchar(10) NOT NULL DEFAULT 'CURRENT',
    agreed_at              timestamptz NOT NULL DEFAULT now(),
    confirmed_by           uuid NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_point_assignments_budget_id_key UNIQUE (budget_id, id),
    CONSTRAINT task_point_assignments_recipient_key UNIQUE (id, recipient_id),
    CONSTRAINT task_point_assignments_status_chk CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    CONSTRAINT task_point_assignments_first_chk CHECK (previous_assignment_id IS NOT NULL OR (help_request_id IS NULL AND support_work_id IS NULL)),
    CONSTRAINT fk_assignment_budget FOREIGN KEY (project_id, budget_id) REFERENCES task_point_budgets (project_id, id),
    CONSTRAINT fk_assignment_previous FOREIGN KEY (budget_id, previous_assignment_id) REFERENCES task_point_assignments (budget_id, id),
    CONSTRAINT fk_assignment_recipient FOREIGN KEY (project_id, recipient_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_assignment_confirmer FOREIGN KEY (project_id, confirmed_by) REFERENCES project_members (project_id, user_id)
);
CREATE UNIQUE INDEX uq_task_point_assignments_one_current ON task_point_assignments (budget_id) WHERE status = 'CURRENT';
CREATE UNIQUE INDEX uq_task_point_assignments_previous
    ON task_point_assignments (previous_assignment_id) WHERE previous_assignment_id IS NOT NULL;
CREATE INDEX idx_task_point_assignments_recipient ON task_point_assignments (project_id, recipient_id) WHERE status = 'CURRENT';

-- ---------------------------------------------------------------------------
-- task_point_settlements: quyết định ghi điểm (sau TL chấp nhận). Tối đa một settlement chưa thu hồi/budget.
-- recipient_id lấy từ assignment (FK ghép đảm bảo khớp).
-- ---------------------------------------------------------------------------
CREATE TABLE task_point_settlements (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id              uuid NOT NULL,
    budget_id               uuid NOT NULL,
    assignment_id           uuid NOT NULL,
    recipient_id            uuid NOT NULL,
    accepted_submission_id  uuid NOT NULL REFERENCES task_submissions (id),
    contribution_id         uuid REFERENCES contributions (id),
    approved_by             uuid NOT NULL,
    approved_at             timestamptz NOT NULL DEFAULT now(),
    revoked_at              timestamptz,
    revoked_by              uuid,
    revoke_reason           text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_point_settlements_budget_id_key UNIQUE (budget_id, id),
    CONSTRAINT task_point_settlements_recipient_key UNIQUE (id, recipient_id),
    CONSTRAINT task_point_settlements_revoke_chk CHECK (
        (revoked_at IS NULL) = (revoke_reason IS NULL) AND (revoked_at IS NULL) = (revoked_by IS NULL)),
    -- không ai tự duyệt khoản điểm mình nhận
    CONSTRAINT task_point_settlements_not_self_chk CHECK (approved_by <> recipient_id),
    CONSTRAINT fk_settlement_budget FOREIGN KEY (project_id, budget_id) REFERENCES task_point_budgets (project_id, id),
    CONSTRAINT fk_settlement_assignment FOREIGN KEY (budget_id, assignment_id) REFERENCES task_point_assignments (budget_id, id),
    CONSTRAINT fk_settlement_recipient FOREIGN KEY (assignment_id, recipient_id) REFERENCES task_point_assignments (id, recipient_id),
    CONSTRAINT fk_settlement_approver FOREIGN KEY (project_id, approved_by) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_settlement_revoker FOREIGN KEY (project_id, revoked_by) REFERENCES project_members (project_id, user_id)
);
-- một task chỉ có MỘT khoản điểm hiệu lực
CREATE UNIQUE INDEX uq_task_point_settlements_one_active ON task_point_settlements (budget_id) WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- point_ledger_entries: sổ điểm APPEND-ONLY (trigger chặn UPDATE/DELETE ở V7)
-- AWARD = +P_t đúng một lần/settlement; REVERSAL = -P_t của khoản gốc, mỗi khoản chỉ đảo một lần.
-- ---------------------------------------------------------------------------
CREATE TABLE point_ledger_entries (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid NOT NULL,
    budget_id         uuid NOT NULL,
    settlement_id     uuid NOT NULL,
    recipient_id      uuid NOT NULL,
    delta_units       bigint NOT NULL,
    entry_type        varchar(10) NOT NULL,
    reverses_entry_id uuid,
    idempotency_key   varchar(200) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT point_ledger_entries_idem_key UNIQUE (idempotency_key),
    CONSTRAINT point_ledger_entries_reverse_ref_key UNIQUE (id, budget_id, settlement_id, recipient_id),
    CONSTRAINT point_ledger_entries_type_chk CHECK (
        (entry_type = 'AWARD' AND delta_units > 0 AND reverses_entry_id IS NULL)
        OR (entry_type = 'REVERSAL' AND delta_units < 0 AND reverses_entry_id IS NOT NULL)),
    CONSTRAINT fk_ledger_budget FOREIGN KEY (project_id, budget_id) REFERENCES task_point_budgets (project_id, id),
    CONSTRAINT fk_ledger_settlement FOREIGN KEY (budget_id, settlement_id) REFERENCES task_point_settlements (budget_id, id),
    CONSTRAINT fk_ledger_settlement_recipient FOREIGN KEY (settlement_id, recipient_id)
        REFERENCES task_point_settlements (id, recipient_id),
    CONSTRAINT fk_ledger_recipient FOREIGN KEY (project_id, recipient_id) REFERENCES project_members (project_id, user_id),
    -- khoản đảo phải cùng budget/settlement/người nhận với khoản gốc
    CONSTRAINT fk_ledger_reverses FOREIGN KEY (reverses_entry_id, budget_id, settlement_id, recipient_id)
        REFERENCES point_ledger_entries (id, budget_id, settlement_id, recipient_id)
);
-- một AWARD/settlement; mỗi khoản AWARD chỉ bị đảo một lần
CREATE UNIQUE INDEX uq_point_ledger_one_award_per_settlement ON point_ledger_entries (settlement_id) WHERE entry_type = 'AWARD';
CREATE UNIQUE INDEX uq_point_ledger_reverses ON point_ledger_entries (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;
CREATE INDEX idx_point_ledger_recipient_time ON point_ledger_entries (project_id, recipient_id, created_at);
CREATE INDEX idx_point_ledger_budget ON point_ledger_entries (budget_id);
