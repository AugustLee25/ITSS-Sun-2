-- HANARE V5: thông báo bền vững, chống trùng. Spec 12.7.
-- Event ghi cùng transaction với thay đổi nghiệp vụ. dedup_key UNIQUE + UNIQUE(event_id, recipient_id) là chốt chặn cuối.
-- dedup_key mẫu: checkpoint:{cycleId}:reminder | checkpoint:{cycleId}:review-required |
--                plan:{revisionId}:submitted | plan:{revisionId}:approved
-- payload KHÔNG chứa nội dung riêng tư nếu người nhận không có quyền xem.

CREATE TABLE notification_events (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  uuid NOT NULL REFERENCES projects (id),
    event_type  varchar(60) NOT NULL,                    -- vd. CHECKPOINT_REMINDER, NEED_HELP, HELP_PUBLISHED ...
    entity_type varchar(60) NOT NULL,
    entity_id   uuid NOT NULL,
    cycle_id    uuid,
    dedup_key   text NOT NULL,
    payload     jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_events_dedup_key UNIQUE (dedup_key),
    CONSTRAINT notification_events_project_id_key UNIQUE (project_id, id),
    CONSTRAINT fk_notification_event_cycle FOREIGN KEY (project_id, cycle_id) REFERENCES checkpoint_cycles (project_id, id)
);
CREATE INDEX idx_notification_events_entity ON notification_events (project_id, entity_type, entity_id);
CREATE INDEX idx_notification_events_cycle ON notification_events (cycle_id) WHERE cycle_id IS NOT NULL;
CREATE TRIGGER trg_notification_events_immutable
    BEFORE UPDATE OR DELETE ON notification_events
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();

CREATE TABLE notifications (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     uuid NOT NULL,
    project_id   uuid NOT NULL,
    recipient_id uuid NOT NULL,
    title        varchar(200) NOT NULL,
    message      text NOT NULL,
    read_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_event_recipient_key UNIQUE (event_id, recipient_id),
    CONSTRAINT fk_notification_event FOREIGN KEY (project_id, event_id) REFERENCES notification_events (project_id, id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (project_id, recipient_id)
        REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_notifications_recipient_time ON notifications (recipient_id, created_at DESC);
CREATE INDEX idx_notifications_recipient_unread ON notifications (recipient_id, created_at DESC) WHERE read_at IS NULL;

-- Chỉ read_at được đổi; không xóa. (Đã đọc != đã xử lý công việc.)
CREATE FUNCTION hanare_notifications_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Table notifications: DELETE is not allowed' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.id, NEW.event_id, NEW.project_id, NEW.recipient_id, NEW.title, NEW.message, NEW.created_at)
        IS DISTINCT FROM (OLD.id, OLD.event_id, OLD.project_id, OLD.recipient_id, OLD.title, OLD.message, OLD.created_at) THEN
        RAISE EXCEPTION 'Table notifications: only read_at may change' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_notifications_guard
    BEFORE UPDATE OR DELETE ON notifications
    FOR EACH ROW EXECUTE FUNCTION hanare_notifications_guard();
