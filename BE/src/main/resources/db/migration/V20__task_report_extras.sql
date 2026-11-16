-- HANARE V20 (BE-B1): cột/bảng bổ sung cho task/báo cáo checkpoint theo API_CONTRACT (xem SPEC_ISSUES [API→DB]).

-- Lý do thay đổi của bản sửa kế hoạch (bản sau khi đã duyệt).
ALTER TABLE task_plan_revisions ADD COLUMN change_reason text;

-- Người thực hiện bấm "Bắt đầu"/ghi tiến độ lần đầu trong vòng (TODO -> IN_PROGRESS).
ALTER TABLE task_review_rounds ADD COLUMN work_started_at timestamptz;

-- Khóa lạc quan của báo cáo (chỉ bản DRAFT đổi được).
ALTER TABLE checkpoint_reports ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- "Ngày dự kiến hoàn thành mới" trong báo cáo (chỉ thông tin, không đổi deadline).
ALTER TABLE checkpoint_report_items ADD COLUMN expected_completion_at timestamptz;

-- Ghi chú tiến độ của người thực hiện (không đổi deadline/cam kết, BR04).
CREATE TABLE task_progress_notes (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id              uuid NOT NULL,
    task_id                 uuid NOT NULL,
    round_id                uuid NOT NULL,
    author_id               uuid NOT NULL,
    note                    text NOT NULL,
    risk_status             varchar(10),
    expected_completion_at  timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT task_progress_notes_note_chk CHECK (btrim(note) <> ''),
    CONSTRAINT task_progress_notes_risk_chk CHECK (risk_status IS NULL OR risk_status IN ('NORMAL', 'UNKNOWN', 'AT_RISK', 'BLOCKED')),
    CONSTRAINT fk_progress_round FOREIGN KEY (project_id, round_id) REFERENCES task_review_rounds (project_id, id),
    CONSTRAINT fk_progress_task FOREIGN KEY (project_id, task_id) REFERENCES tasks (project_id, id),
    CONSTRAINT fk_progress_author FOREIGN KEY (project_id, author_id) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_task_progress_notes_round ON task_progress_notes (round_id, created_at DESC);
CREATE TRIGGER trg_task_progress_notes_immutable
    BEFORE UPDATE OR DELETE ON task_progress_notes
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();

-- Ảnh chụp LeadCycleSummary tại giờ chốt (bất biến): GET lead-summary?view=AT_CUTOFF.
CREATE TABLE checkpoint_cycle_summaries (
    cycle_id   uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    summary    jsonb NOT NULL,
    frozen_at  timestamptz NOT NULL,
    CONSTRAINT fk_cycle_summary_cycle FOREIGN KEY (project_id, cycle_id) REFERENCES checkpoint_cycles (project_id, id)
);
CREATE TRIGGER trg_checkpoint_cycle_summaries_immutable
    BEFORE UPDATE OR DELETE ON checkpoint_cycle_summaries
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();
