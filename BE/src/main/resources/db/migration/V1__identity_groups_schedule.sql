-- HANARE V1: định danh, nhóm (project), thành viên, lời mời, lịch checkpoint, kỳ checkpoint.
-- Spec 12.2-12.3. "project" = nhóm (không có bảng groups). Vai trò: LEAD (TL) / MEMBER.
-- Quy ước: uuid PK, timestamptz (UTC), enum = varchar + CHECK, không ON DELETE CASCADE.

-- ---------------------------------------------------------------------------
-- Hàm dùng chung
-- ---------------------------------------------------------------------------
-- Chặn UPDATE/DELETE trên bảng append-only / bất biến (ledger, audit, lần nộp, lịch...).
CREATE FUNCTION hanare_block_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only/immutable: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

-- Chặn hard-delete để giữ lịch sử (task, kỳ, đóng góp, quỹ điểm...).
CREATE FUNCTION hanare_forbid_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Hard delete on % is not allowed (history must be kept)', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

-- Múi giờ IANA hợp lệ (dùng trong CHECK).
CREATE FUNCTION hanare_valid_timezone(tz text) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = tz)
$$;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          varchar(100) NOT NULL,
    email         varchar(254) NOT NULL,
    password_hash varchar(100),                      -- NULL = tài khoản chưa đặt mật khẩu (OAuth sau này)
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_name_not_blank CHECK (char_length(btrim(name)) >= 1),
    -- email đã chuẩn hóa (chữ thường, không khoảng trắng) nên UNIQUE thường là đủ
    CONSTRAINT users_email_normalized CHECK (email = lower(btrim(email)) AND position('@' IN email) > 1),
    CONSTRAINT users_email_key UNIQUE (email)
);

-- ---------------------------------------------------------------------------
-- projects (= nhóm)
-- ---------------------------------------------------------------------------
CREATE TABLE projects (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(80) NOT NULL,
    course_name varchar(120),                         -- tên môn (tùy chọn, S02)
    start_at    timestamptz NOT NULL,                 -- thời điểm bắt đầu dự án, mốc period_start_at của kỳ đầu
    deadline    timestamptz NOT NULL,                 -- deadline tổng thể
    timezone    text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    created_by  uuid NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    version     bigint NOT NULL DEFAULT 0,
    CONSTRAINT projects_name_len CHECK (char_length(btrim(name)) BETWEEN 3 AND 80),
    CONSTRAINT projects_deadline_after_start CHECK (deadline >= start_at),
    CONSTRAINT projects_timezone_valid CHECK (hanare_valid_timezone(timezone))
);

-- ---------------------------------------------------------------------------
-- project_members (giữ lịch sử bằng left_at, không xóa)
-- ---------------------------------------------------------------------------
CREATE TABLE project_members (
    project_id uuid NOT NULL REFERENCES projects (id),
    user_id    uuid NOT NULL REFERENCES users (id),
    role       varchar(10) NOT NULL,
    joined_at  timestamptz NOT NULL DEFAULT now(),
    left_at    timestamptz,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT project_members_role_chk CHECK (role IN ('LEAD', 'MEMBER')),
    CONSTRAINT project_members_left_after_join CHECK (left_at IS NULL OR left_at >= joined_at)
);
-- Tối đa một LEAD đang hoạt động/nhóm. Chuyển TL: hạ TL cũ xuống MEMBER trước, rồi nâng TL mới (cùng transaction).
CREATE UNIQUE INDEX uq_project_members_one_active_leader
    ON project_members (project_id) WHERE role = 'LEAD' AND left_at IS NULL;
CREATE INDEX idx_project_members_user ON project_members (user_id) WHERE left_at IS NULL;

-- ---------------------------------------------------------------------------
-- project_invitations (S02: mã/link mời; spec mục 12 không định nghĩa, bổ sung tối thiểu)
-- ---------------------------------------------------------------------------
CREATE TABLE project_invitations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES projects (id),
    code       varchar(40) NOT NULL,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT project_invitations_code_key UNIQUE (code),
    CONSTRAINT fk_invitation_creator FOREIGN KEY (project_id, created_by)
        REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_project_invitations_project ON project_invitations (project_id);

-- ---------------------------------------------------------------------------
-- checkpoint_schedules: lịch lặp hằng tuần, phiên bản bất biến
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoint_schedules (
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id               uuid NOT NULL REFERENCES projects (id),
    version_no               int NOT NULL,
    weekday                  smallint NOT NULL,       -- 1 = Thứ Hai ... 7 = Chủ nhật (ISO)
    cutoff_local_time        time NOT NULL,           -- giờ chốt theo múi giờ của lịch
    timezone                 text NOT NULL,
    reminder_before_minutes  int NOT NULL DEFAULT 420, -- 7 giờ
    reporting_window_minutes int,                     -- NULL = mở báo cáo trong cả kỳ
    effective_from           timestamptz NOT NULL,    -- chỉ áp dụng cho các kỳ chưa mở kể từ mốc này
    agreed_at                timestamptz NOT NULL,    -- TL ghi nhận nhóm đã thống nhất
    recorded_by              uuid NOT NULL,
    source                   varchar(10) NOT NULL DEFAULT 'APP',
    created_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT checkpoint_schedules_version_key UNIQUE (project_id, version_no),
    CONSTRAINT checkpoint_schedules_project_id_key UNIQUE (project_id, id),
    CONSTRAINT checkpoint_schedules_weekday_chk CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT checkpoint_schedules_version_pos CHECK (version_no >= 1),
    CONSTRAINT checkpoint_schedules_reminder_chk CHECK (reminder_before_minutes > 0),
    CONSTRAINT checkpoint_schedules_window_chk CHECK (reporting_window_minutes IS NULL OR reporting_window_minutes > 0),
    CONSTRAINT checkpoint_schedules_tz_chk CHECK (hanare_valid_timezone(timezone)),
    CONSTRAINT checkpoint_schedules_source_chk CHECK (source IN ('APP', 'LEGACY')),
    CONSTRAINT fk_schedule_recorder FOREIGN KEY (project_id, recorded_by)
        REFERENCES project_members (project_id, user_id)
);
CREATE INDEX idx_checkpoint_schedules_project ON checkpoint_schedules (project_id, effective_from DESC);
CREATE TRIGGER trg_checkpoint_schedules_immutable
    BEFORE UPDATE OR DELETE ON checkpoint_schedules
    FOR EACH ROW EXECUTE FUNCTION hanare_block_mutation();

-- ---------------------------------------------------------------------------
-- checkpoint_cycles: kỳ (period_start_at, closes_at]
-- Kỳ N bắt đầu đúng tại closes_at của kỳ N-1 (FK ghép) nên các kỳ không thể chồng lấn/hở.
-- ---------------------------------------------------------------------------
CREATE TABLE checkpoint_cycles (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid NOT NULL REFERENCES projects (id),
    schedule_id       uuid NOT NULL,
    sequence_no       int NOT NULL,
    previous_cycle_id uuid,                           -- NULL chỉ ở kỳ đầu
    period_start_at   timestamptz NOT NULL,           -- loại trừ
    opens_at          timestamptz NOT NULL,
    reminder_at       timestamptz NOT NULL,           -- = closes_at - reminder_before_minutes của lịch
    closes_at         timestamptz NOT NULL,           -- bao gồm (deadline = closes_at thuộc kỳ này)
    status            varchar(12) NOT NULL DEFAULT 'SCHEDULED',
    closed_at         timestamptz,
    finalized_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint NOT NULL DEFAULT 0,
    CONSTRAINT checkpoint_cycles_project_id_key UNIQUE (project_id, id),
    CONSTRAINT checkpoint_cycles_project_seq_key UNIQUE (project_id, sequence_no),
    CONSTRAINT checkpoint_cycles_chain_key UNIQUE (project_id, id, closes_at),
    CONSTRAINT checkpoint_cycles_status_chk CHECK (status IN ('SCHEDULED', 'OPEN', 'CLOSED', 'FINALIZED', 'CANCELLED')),
    CONSTRAINT checkpoint_cycles_seq_pos CHECK (sequence_no >= 1),
    CONSTRAINT checkpoint_cycles_period_chk CHECK (period_start_at < closes_at),
    CONSTRAINT checkpoint_cycles_open_chk CHECK (period_start_at <= opens_at AND opens_at < closes_at),
    CONSTRAINT checkpoint_cycles_reminder_chk CHECK (reminder_at < closes_at),
    CONSTRAINT checkpoint_cycles_finalized_chk CHECK ((status = 'FINALIZED') = (finalized_at IS NOT NULL)),
    CONSTRAINT fk_cycle_schedule FOREIGN KEY (project_id, schedule_id)
        REFERENCES checkpoint_schedules (project_id, id),
    -- kỳ trước phải cùng nhóm và period_start_at == closes_at của kỳ trước
    CONSTRAINT fk_cycle_previous FOREIGN KEY (project_id, previous_cycle_id, period_start_at)
        REFERENCES checkpoint_cycles (project_id, id, closes_at)
);
-- cutoff duy nhất/nhóm (kỳ CANCELLED không chiếm chỗ để lịch mới có thể sinh lại kỳ cùng cutoff)
CREATE UNIQUE INDEX uq_checkpoint_cycles_project_closes
    ON checkpoint_cycles (project_id, closes_at) WHERE status <> 'CANCELLED';
-- mỗi kỳ chỉ có tối đa một kỳ kế tiếp; chỉ một kỳ đầu/nhóm
CREATE UNIQUE INDEX uq_checkpoint_cycles_next
    ON checkpoint_cycles (previous_cycle_id) WHERE previous_cycle_id IS NOT NULL AND status <> 'CANCELLED';
CREATE UNIQUE INDEX uq_checkpoint_cycles_first
    ON checkpoint_cycles (project_id) WHERE previous_cycle_id IS NULL AND status <> 'CANCELLED';
-- job đến hạn
CREATE INDEX idx_checkpoint_cycles_status_reminder ON checkpoint_cycles (status, reminder_at);
CREATE INDEX idx_checkpoint_cycles_status_closes ON checkpoint_cycles (status, closes_at);
