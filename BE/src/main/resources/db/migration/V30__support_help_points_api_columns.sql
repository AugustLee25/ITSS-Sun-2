-- HANARE V30 (BE-B2): cột/bảng cần thêm cho hợp đồng API của support / help / points (xem SPEC_ISSUES [API→DB]).
-- `support_requests.round_id`, `help_requests.effort_estimate_minutes/skill_note` đã có ở V4 nên dùng lại, không thêm cột trùng nghĩa.

-- support_requests: "ngày dự kiến nếu biết" là ngày lịch (IsoDate), không phải thời điểm
ALTER TABLE support_requests ADD COLUMN expected_date date;

-- risk_reports: task bị ảnh hưởng (để phát RISK_RELEVANT), version (khóa lạc quan), ghi chú khi giải quyết
ALTER TABLE risk_reports ADD COLUMN affected_task_ids uuid[] NOT NULL DEFAULT '{}';
ALTER TABLE risk_reports ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE risk_reports ADD COLUMN resolved_by uuid;
ALTER TABLE risk_reports ADD COLUMN resolve_note text;
ALTER TABLE risk_reports ADD CONSTRAINT fk_risk_resolver FOREIGN KEY (project_id, resolved_by)
    REFERENCES project_members (project_id, user_id);

-- support_offers: phạm vi yêu cầu đổi quan trọng sau khi gửi -> Helper phải xác nhận lại
ALTER TABLE support_offers ADD COLUMN needs_reconfirmation boolean NOT NULL DEFAULT false;

-- support_submissions <-> task_submissions: một lần nộp của Helper tạo đúng một lần nộp trên task gốc
ALTER TABLE support_submissions ADD COLUMN task_submission_id uuid REFERENCES task_submissions (id);
CREATE UNIQUE INDEX uq_support_submissions_task_submission
    ON support_submissions (task_submission_id) WHERE task_submission_id IS NOT NULL;

-- support_works: yêu cầu mở lại (REOPEN_REQUEST) và lý do Helper báo không thể hoàn thành
ALTER TABLE support_works ADD COLUMN successor_help_request_id uuid REFERENCES help_requests (id);
ALTER TABLE support_works ADD COLUMN unable_reason text;

-- timeline S10 (append-only)
CREATE TABLE support_work_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seq         bigint GENERATED ALWAYS AS IDENTITY,       -- thứ tự chèn khi cùng thời điểm
    project_id  uuid NOT NULL,
    work_id     uuid NOT NULL REFERENCES support_works (id),
    kind        varchar(24) NOT NULL,
    actor_id    uuid,
    note        text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT support_work_events_kind_chk CHECK (kind IN
        ('OFFER_ACCEPTED', 'RESULT_SUBMITTED', 'REVISION_REQUESTED', 'UNABLE_REPORTED', 'DISCUSSION_RESOLVED', 'APPROVED')),
    CONSTRAINT fk_work_event_actor FOREIGN KEY (project_id, actor_id) REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_support_work_events_work ON support_work_events (work_id, occurred_at, seq);
CREATE TRIGGER trg_support_work_events_append_only
    BEFORE UPDATE OR DELETE ON support_work_events
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();
