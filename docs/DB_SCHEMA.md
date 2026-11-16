# HANARE — Tham chiếu nhanh DB (Flyway V1–V7, PostgreSQL 16)

Nguồn: spec 12. File: `BE/src/main/resources/db/migration/V1..V7`. Test dựng DB sạch: `AbstractDbTest` / `@HanareTest`; dữ liệu mẫu: `DbFixtures`.
Quy ước: PK `uuid` (`gen_random_uuid()`); thời gian `timestamptz` (bind bằng `OffsetDateTime`, xem `DbTime`); enum = `varchar + CHECK` (chuỗi đúng spec 6/12, vai trò **`LEAD`**/`MEMBER`); điểm = `bigint point_units` (1.000.000 = 100%, 1 điểm % = 10.000); `project_id` = nhóm. FK **ghép** `(project_id, x)` → `project_members(project_id,user_id)`/bảng cùng nhóm ⇒ DB tự chặn dữ liệu chéo nhóm. Không cascade, không hard-delete bảng lịch sử (trigger). Vi phạm ràng buộc → `DataIntegrityViolationException` (service nên tự kiểm tra trước để trả mã nghiệp vụ).

| Bảng (file) | Cột chính | Ràng buộc đáng nhớ |
|---|---|---|
| `users` (V1) | name, email (đã lowercase), password_hash | email UNIQUE + CHECK chuẩn hóa |
| `projects` (V1) | name, course_name, start_at, deadline, timezone, archived_at, version | timezone IANA hợp lệ; deadline ≥ start_at |
| `project_members` (V1) | PK(project_id,user_id), role, joined_at, left_at | tối đa 1 LEAD đang hoạt động/nhóm (chuyển TL: hạ cũ trước, nâng mới sau) |
| `project_invitations` (V1) | code UNIQUE, expires_at, revoked_at | (spec không định nghĩa, bổ sung cho S02) |
| `checkpoint_schedules` (V1) | version_no, weekday 1-7, cutoff_local_time, timezone, reminder_before_minutes=420, reporting_window_minutes, effective_from, agreed_at, recorded_by | **bất biến** (chặn UPDATE/DELETE); UNIQUE(project,version_no) |
| `checkpoint_cycles` (V1) | sequence_no, previous_cycle_id, period_start_at, opens_at, reminder_at, closes_at, status | kỳ = (period_start_at, closes_at]; FK ghép `(project,previous,period_start_at)→(project,id,closes_at)` ⇒ kỳ nối liền, không chồng/hở; status chỉ đi tiến; biên bất biến sau khi mở |
| `tasks` (V2) | created_by, independent_approver_id (+set_by/at), archived_at, version | định danh xuyên kỳ; không lưu trạng thái tổng |
| `task_review_rounds` (V2) | task_id, round_no, cycle_id, previous_round_id, reason, state, effective_plan_id, accepted_submission_id, completed_at, accepted_at | tối đa 1 vòng chưa kết thúc/task; vòng 2+ phải có previous; effective_plan_id → revision APPROVED cùng vòng + cùng kỳ (FK ghép + trigger) |
| `task_plan_revisions` (V2) | round_id, revision_no, base_revision_id, proposed_cycle_id, assignee_id, title, description, expected_output, start_at, deadline, difficulty, status, submitted_*/reviewed_*, review_note, approval_source | tối đa 1 bản chưa APPROVED/vòng; PENDING/APPROVED phải đủ trường; deadline ∈ kỳ đề xuất (trigger); APPROVED bất biến |
| `task_submissions` (V2) | round_id, plan_revision_id, submission_no, submitted_by, evidence jsonb (mảng ≥1: `{type:"URL",url,label}`), submitted_at | bất biến; UNIQUE(round,no) |
| `task_result_reviews` (V2) | submission_id UNIQUE, decision, reviewer_id, issue_description, reviewed_at | FIX/UPDATE bắt buộc có mô tả |
| `task_carryovers` (V2) | source_round_id, target_round_id, source_review_id, decided_by, decision_reason | UNIQUE nguồn, UNIQUE đích |
| `audit_logs` (V2) | project_id, actor_id, entity_type, entity_id, action, before_data, after_data, correlation_id | **append-only** |
| `checkpoint_participants` (V3) | cycle_id, member_id, role_at_cutoff, required_task_count, report_id_at_cutoff, cutoff_summary, final_summary, frozen_at | UNIQUE(cycle,member); dữ liệu cutoff bất biến sau frozen_at |
| `checkpoint_task_snapshots` (V3) | cycle_id, round_id, assignee_id, approved_plan_id, title/deadline/difficulty_snapshot, submission_id_at_cutoff, execution_state_at_cutoff, frozen_at | UNIQUE(cycle,round); bất biến sau frozen_at |
| `checkpoint_reports` / `_items` (V3) | participant_id, revision_no, status, submitted_at / snapshot_id, declared_result, risk_status, needs_lead_help, submission_id | 1 DRAFT/participant; SUBMITTED bất biến; item phải của đúng thành viên + kỳ (FK ghép); RESULT_SUBMITTED cần submission_id |
| `support_requests` (V4) | task_id, requester_id, obstacle_type, description, help_needed, impact, status, decision, lead_note | riêng tư (chỉ người gửi + TL) |
| `help_requests` (V4) | support_request_id, task_id, beneficiary_id, result_approver_id, created_by, title, description, expected_output, difficulty, deadline, task_point_budget_id, advertised_point_units, status | 1 request còn hiệu lực/task; giá = đúng P_t (FK ghép V7); approver ≠ beneficiary |
| `support_offers` / `support_works` / `support_submissions` (V4) | offer: helper_id,status; work: accepted_offer_id, helper_id, result_approver_id, snapshots, status; submission: revision_no, evidence, actual_minutes | 1 offer hoạt động/người; 1 ACCEPTED/request; UNIQUE work/request; helper ≠ beneficiary/approver/TL đăng |
| `contributions` (V4) | work_id UNIQUE, accepted_submission_id, helper_id, status, confirmed_by/at | CONFIRMED cần người xác nhận ≠ helper |
| `risk_reports` (V4) | subject_member_id, reported_by, risk_type(DELAY_RISK/BLOCKED/DROPOUT_RISK), public_summary, status, confirmed_by | |
| `notification_events` / `notifications` (V5) | event: event_type, entity_type/id, cycle_id, dedup_key UNIQUE, payload; notification: event_id, recipient_id, title, message, read_at | UNIQUE(event,recipient); chỉ `read_at` đổi được; event bất biến |
| `point_policies` (V6) | version_no, total_units=1000000, easy/medium/hard_weight, member_count_at_baseline, status | 1 ACTIVE/nhóm; tạo ở DRAFT, kích hoạt khi Σ initial=1.000.000 và mỗi người reserve+Σ task=initial |
| `member_point_budgets` (V6) | policy_id, member_id, initial_units, reserve_units, baseline_weight_sum | chỉ reserve_units đổi được |
| `task_point_budgets` (V6) | origin_member_budget_id, task_id UNIQUE, difficulty/weight_at_lock, point_units, locked_plan_id, state | giá P_t **không đổi** |
| `task_point_reservations` (V6) | budget_id, help_request_id, point_units, state HELD/CONSUMED/RELEASED | 1 HELD/budget; point_units = đúng P_t (FK ghép) |
| `task_point_assignments` (V6) | budget_id, recipient_id, previous_assignment_id, help_request_id, support_work_id, status | 1 CURRENT/budget |
| `task_point_settlements` (V6) | budget_id, assignment_id, recipient_id, accepted_submission_id, contribution_id, approved_by, revoked_* | 1 settlement chưa thu hồi/budget; chỉ ghi khi bản nộp đã ACCEPTED (+contribution CONFIRMED nếu có Helper); approver ≠ recipient |
| `point_ledger_entries` (V6) | budget_id, settlement_id, recipient_id, delta_units, entry_type AWARD/REVERSAL, reverses_entry_id, idempotency_key UNIQUE | **append-only**; AWARD đúng +P_t, 1/settlement; REVERSAL đúng −khoản gốc, đảo 1 lần; ròng/budget ∈ [0,P_t] |

Sửa migration tại chỗ được cho tới khi có bản phát hành (mọi DB test/dev dựng lại từ đầu); mỗi lần sửa chạy lại `MigrationTest` + `ConstraintsTest`.
