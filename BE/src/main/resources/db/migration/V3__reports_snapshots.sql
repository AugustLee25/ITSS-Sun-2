-- HANARE V3: người tham gia kỳ, snapshot task tại giờ chốt, báo cáo kỳ + từng mục task.
-- Spec 12.3 (checkpoint_participants), 12.5. Không dựng lại lịch sử từ tasks hiện tại.
-- Chỉ snapshot đã frozen_at và báo cáo đã SUBMITTED là bất biến (trigger ở V7).

-- ---------------------------------------------------------------------------
-- checkpoint_participants: mỗi thành viên/kỳ (tạo khi cần, đóng băng ở giờ chốt)
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoint_participants (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id             uuid NOT NULL,
    project_id           uuid NOT NULL,
    member_id            uuid NOT NULL,
    role_at_cutoff       varchar(10),                    -- NULL đến khi đóng băng
    required_task_count  int NOT NULL DEFAULT 0,
    report_id_at_cutoff  uuid,                           -- bản báo cáo SUBMITTED gần nhất đúng hạn (FK ghép ở cuối file)
    cutoff_summary       jsonb NOT NULL DEFAULT '{}'::jsonb,
    final_summary        jsonb,                          -- ghi sau TL kiểm tra, không ghi đè cutoff_summary
    frozen_at            timestamptz,
    finalized_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT checkpoint_participants_cycle_member_key UNIQUE (cycle_id, member_id),
    CONSTRAINT checkpoint_participants_ref_key UNIQUE (id, cycle_id, member_id),
    CONSTRAINT checkpoint_participants_role_chk CHECK (role_at_cutoff IS NULL OR role_at_cutoff IN ('LEAD', 'MEMBER')),
    CONSTRAINT checkpoint_participants_count_chk CHECK (required_task_count >= 0),
    CONSTRAINT checkpoint_participants_frozen_chk CHECK (frozen_at IS NULL OR role_at_cutoff IS NOT NULL),
    CONSTRAINT fk_participant_cycle FOREIGN KEY (project_id, cycle_id) REFERENCES checkpoint_cycles (project_id, id),
    CONSTRAINT fk_participant_member FOREIGN KEY (project_id, member_id) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_checkpoint_participants_member ON checkpoint_participants (project_id, member_id);

-- ---------------------------------------------------------------------------
-- checkpoint_task_snapshots: danh sách task bắt buộc của kỳ (từ kế hoạch ĐÃ DUYỆT)
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoint_task_snapshots (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id                 uuid NOT NULL,
    project_id               uuid NOT NULL,
    round_id                 uuid NOT NULL,
    task_id                  uuid NOT NULL,
    assignee_id              uuid NOT NULL,
    approved_plan_id         uuid NOT NULL,
    title_snapshot           varchar(200) NOT NULL,
    deadline_snapshot        timestamptz NOT NULL,
    difficulty_snapshot      varchar(8) NOT NULL,
    submission_id_at_cutoff  uuid,
    execution_state_at_cutoff varchar(16) NOT NULL DEFAULT 'TODO',
    frozen_at                timestamptz,                -- NULL = bản chuẩn bị, còn đổi được; có giá trị = bất biến
    created_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT checkpoint_task_snapshots_cycle_round_key UNIQUE (cycle_id, round_id),
    CONSTRAINT checkpoint_task_snapshots_item_ref_key UNIQUE (id, cycle_id, assignee_id, round_id),
    CONSTRAINT checkpoint_task_snapshots_diff_chk CHECK (difficulty_snapshot IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT checkpoint_task_snapshots_state_chk CHECK (execution_state_at_cutoff IN
        ('TODO', 'IN_PROGRESS', 'SUBMITTED', 'FIX_REQUIRED', 'UPDATE_REQUIRED', 'DONE')),
    CONSTRAINT fk_snapshot_cycle FOREIGN KEY (project_id, cycle_id) REFERENCES checkpoint_cycles (project_id, id),
    CONSTRAINT fk_snapshot_round FOREIGN KEY (project_id, round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_snapshot_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_snapshot_assignee FOREIGN KEY (project_id, assignee_id) REFERENCES project_members (project_id, user_id),
    CONSTRAINT fk_snapshot_plan FOREIGN KEY (round_id, approved_plan_id) REFERENCES task_plan_revisions (round_id, id),
    CONSTRAINT fk_snapshot_submission FOREIGN KEY (round_id, submission_id_at_cutoff) REFERENCES task_submissions (round_id, id)
);
CREATE INDEX idx_checkpoint_task_snapshots_assignee ON checkpoint_task_snapshots (cycle_id, assignee_id);

-- ---------------------------------------------------------------------------
-- checkpoint_reports: các phiên bản báo cáo của một thành viên trong kỳ
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoint_reports (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    participant_id uuid NOT NULL,
    cycle_id       uuid NOT NULL,
    member_id      uuid NOT NULL,
    revision_no    int NOT NULL,
    status         varchar(10) NOT NULL DEFAULT 'DRAFT',
    submitted_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT checkpoint_reports_participant_rev_key UNIQUE (participant_id, revision_no),
    CONSTRAINT checkpoint_reports_item_ref_key UNIQUE (id, cycle_id, member_id),
    CONSTRAINT checkpoint_reports_rev_pos CHECK (revision_no >= 1),
    CONSTRAINT checkpoint_reports_status_chk CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT checkpoint_reports_submitted_chk CHECK ((status = 'SUBMITTED') = (submitted_at IS NOT NULL)),
    CONSTRAINT fk_report_participant FOREIGN KEY (participant_id, cycle_id, member_id)
        REFERENCES checkpoint_participants (id, cycle_id, member_id)
);
-- tối đa một bản DRAFT/participant
CREATE UNIQUE INDEX uq_checkpoint_reports_one_draft ON checkpoint_reports (participant_id) WHERE status = 'DRAFT';
CREATE INDEX idx_checkpoint_reports_participant_time ON checkpoint_reports (participant_id, submitted_at DESC);

-- ---------------------------------------------------------------------------
-- checkpoint_report_items: kết quả khai cho từng task bắt buộc
-- item phải thuộc task của CHÍNH thành viên và CHÍNH kỳ (FK ghép tới report và snapshot)
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoint_report_items (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id        uuid NOT NULL,
    cycle_id         uuid NOT NULL,
    member_id        uuid NOT NULL,
    snapshot_id      uuid NOT NULL,
    round_id         uuid NOT NULL,
    declared_result  varchar(20) NOT NULL,
    note             text,
    risk_status      varchar(10) NOT NULL DEFAULT 'NORMAL',
    needs_lead_help  boolean NOT NULL DEFAULT false,
    submission_id    uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT checkpoint_report_items_report_snapshot_key UNIQUE (report_id, snapshot_id),
    CONSTRAINT checkpoint_report_items_result_chk CHECK (declared_result IN ('RESULT_SUBMITTED', 'RESULT_MISSING')),
    CONSTRAINT checkpoint_report_items_risk_chk CHECK (risk_status IN ('NORMAL', 'UNKNOWN', 'AT_RISK', 'BLOCKED')),
    -- RESULT_SUBMITTED phải dẫn tới lần nộp minh chứng thật
    CONSTRAINT checkpoint_report_items_evidence_chk CHECK (declared_result <> 'RESULT_SUBMITTED' OR submission_id IS NOT NULL),
    CONSTRAINT fk_report_item_report FOREIGN KEY (report_id, cycle_id, member_id)
        REFERENCES checkpoint_reports (id, cycle_id, member_id),
    CONSTRAINT fk_report_item_snapshot FOREIGN KEY (snapshot_id, cycle_id, member_id, round_id)
        REFERENCES checkpoint_task_snapshots (id, cycle_id, assignee_id, round_id),
    CONSTRAINT fk_report_item_submission FOREIGN KEY (round_id, submission_id)
        REFERENCES task_submissions (round_id, id)
);
CREATE INDEX idx_checkpoint_report_items_snapshot ON checkpoint_report_items (snapshot_id);

-- vòng tròn participants <-> reports: bản chốt phải thuộc đúng participant (cùng kỳ, cùng thành viên)
ALTER TABLE checkpoint_participants
    ADD CONSTRAINT fk_participant_cutoff_report FOREIGN KEY (report_id_at_cutoff, cycle_id, member_id)
        REFERENCES checkpoint_reports (id, cycle_id, member_id);
