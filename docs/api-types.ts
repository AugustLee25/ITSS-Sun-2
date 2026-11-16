/**
 * HANARE — api-types.ts
 * Hợp đồng kiểu TypeScript cho REST `/api/v1` (khớp 1-1 với docs/API_CONTRACT.md).
 * Nguồn sự thật nghiệp vụ: HANARE_UXUI_Screen_Spec.md (v1.2). KHÔNG sửa tay một nửa: đổi ở đây thì đổi cả API_CONTRACT.md.
 *
 * Quy ước chung
 *  - Tên trường camelCase. Mọi id là UUID dạng chuỗi (`Uuid`).
 *  - Thời điểm = ISO-8601 UTC có hậu tố `Z` (`IsoInstant`). Ngày lịch = `IsoDate` (YYYY-MM-DD, theo múi giờ nhóm).
 *  - Điểm = số nguyên đơn vị (spec 12.8): `PointAmount { pointUnits: number; percentLabel: string }`.
 *    1.000.000 units = 100% quỹ dự án; 1 điểm % = 10.000 units; 1 unit = 0,0001 điểm %.
 *    `percentLabel` do server định dạng sẵn ("10,0000"); FE KHÔNG tự chia/làm tròn/định dạng lại, chỉ hiển thị.
 *  - Quy tắc nhạy cảm (đúng hạn, kỳ (prev, cur], màu khung tên, P_t, quyền hành động) do SERVER tính; FE chỉ hiển thị.
 */

// ───────────────────────────── Kiểu nguyên thủy ─────────────────────────────

/** UUID dạng chuỗi (chữ thường, 36 ký tự). */
export type Uuid = string;
/** Thời điểm ISO-8601 UTC, vd "2026-11-20T15:00:00Z". */
export type IsoInstant = string;
/** Ngày lịch "YYYY-MM-DD" (theo múi giờ nhóm). */
export type IsoDate = string;
/** Giờ địa phương "HH:mm" (24h). */
export type LocalTime = string;
/** Số nguyên đơn vị điểm (bigint ở DB: point_units). 1.000.000 = 100% quỹ; 1 điểm % = 10.000; 1 unit = 0,0001 điểm %. */
export type PointUnits = number;

/**
 * Một lượng điểm. `percentLabel`: số điểm % đã định dạng vi-VN, đúng 4 chữ số thập phân, dấu phẩy,
 * không dấu phân nhóm, không kèm "+" / "%" (vd "10,0000", "0,0000", "100,0000").
 */
export interface PointAmount {
  pointUnits: PointUnits;
  percentLabel: string;
}

/** Biến động có dấu (khoản ghi/đảo/chênh lệch). `pointUnits` có thể âm; `percentLabel` LUÔN kèm dấu rõ ràng: "+4,0000" | "-4,0000" | "0,0000". */
export type PointDelta = PointAmount;

// ───────────────────────────── Enum chính tắc ─────────────────────────────
// Mẫu: const object + union literal cùng tên (dùng `GroupRole.LEAD` hoặc kiểu `GroupRole`).

export const GroupRole = { LEAD: 'LEAD', MEMBER: 'MEMBER' } as const;
export type GroupRole = (typeof GroupRole)[keyof typeof GroupRole];

export const InviteStatus = { ACTIVE: 'ACTIVE', EXPIRED: 'EXPIRED', REVOKED: 'REVOKED' } as const;
export type InviteStatus = (typeof InviteStatus)[keyof typeof InviteStatus];

export const PlanStatus = {
  DRAFT: 'DRAFT',
  PENDING_APPROVAL: 'PENDING_APPROVAL',
  CHANGES_REQUESTED: 'CHANGES_REQUESTED',
  APPROVED: 'APPROVED',
} as const;
export type PlanStatus = (typeof PlanStatus)[keyof typeof PlanStatus];

export const PlanApprovalSource = {
  MEMBER_PLAN: 'MEMBER_PLAN',
  TL_CARRYOVER: 'TL_CARRYOVER',
  TL_SUPPORT_HANDOFF: 'TL_SUPPORT_HANDOFF',
} as const;
export type PlanApprovalSource = (typeof PlanApprovalSource)[keyof typeof PlanApprovalSource];

export const Difficulty = { EASY: 'EASY', MEDIUM: 'MEDIUM', HARD: 'HARD' } as const;
export type Difficulty = (typeof Difficulty)[keyof typeof Difficulty];

export const TaskStatus = {
  TODO: 'TODO',
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
  FIX_REQUIRED: 'FIX_REQUIRED',
  UPDATE_REQUIRED: 'UPDATE_REQUIRED',
  DONE: 'DONE',
} as const;
export type TaskStatus = (typeof TaskStatus)[keyof typeof TaskStatus];

export const RiskStatus = {
  NORMAL: 'NORMAL',
  UNKNOWN: 'UNKNOWN',
  AT_RISK: 'AT_RISK',
  BLOCKED: 'BLOCKED',
} as const;
export type RiskStatus = (typeof RiskStatus)[keyof typeof RiskStatus];

export const RoundState = {
  DRAFT: 'DRAFT',
  ACTIVE: 'ACTIVE',
  AWAITING_REVIEW: 'AWAITING_REVIEW',
  ACCEPTED: 'ACCEPTED',
  CARRYOVER_REQUIRED: 'CARRYOVER_REQUIRED',
  CARRIED_OVER: 'CARRIED_OVER',
} as const;
export type RoundState = (typeof RoundState)[keyof typeof RoundState];

export const RoundReason = {
  INITIAL: 'INITIAL',
  NOT_SUBMITTED: 'NOT_SUBMITTED',
  FIX_REQUIRED: 'FIX_REQUIRED',
  UPDATE_REQUIRED: 'UPDATE_REQUIRED',
} as const;
export type RoundReason = (typeof RoundReason)[keyof typeof RoundReason];

export const TaskReviewDecision = {
  ACCEPTED: 'ACCEPTED',
  FIX_REQUIRED: 'FIX_REQUIRED',
  UPDATE_REQUIRED: 'UPDATE_REQUIRED',
} as const;
export type TaskReviewDecision = (typeof TaskReviewDecision)[keyof typeof TaskReviewDecision];

/** Tình trạng đúng hạn của một task trong vòng xử lý (server tính theo submitted_at <= deadline). */
export const TaskTimeliness = {
  NOT_DUE: 'NOT_DUE',
  ON_TIME_PENDING_REVIEW: 'ON_TIME_PENDING_REVIEW',
  ON_TIME_ACCEPTED: 'ON_TIME_ACCEPTED',
  LATE_PENDING_REVIEW: 'LATE_PENDING_REVIEW',
  LATE_ACCEPTED: 'LATE_ACCEPTED',
  OVERDUE_NO_RESULT: 'OVERDUE_NO_RESULT',
  NEEDS_REWORK: 'NEEDS_REWORK',
} as const;
export type TaskTimeliness = (typeof TaskTimeliness)[keyof typeof TaskTimeliness];

export const TaskAction = {
  EDIT_PLAN: 'EDIT_PLAN',
  SUBMIT_PLAN: 'SUBMIT_PLAN',
  WITHDRAW_PLAN: 'WITHDRAW_PLAN',
  REVIEW_PLAN: 'REVIEW_PLAN',
  PROPOSE_CHANGE: 'PROPOSE_CHANGE',
  START: 'START',
  UPDATE_PROGRESS: 'UPDATE_PROGRESS',
  REPORT_CHECKPOINT: 'REPORT_CHECKPOINT',
  REPORT_DIFFICULTY: 'REPORT_DIFFICULTY',
  SUBMIT_RESULT: 'SUBMIT_RESULT',
  REVIEW_RESULT: 'REVIEW_RESULT',
  SET_CARRYOVER: 'SET_CARRYOVER',
  DESIGNATE_REVIEWER: 'DESIGNATE_REVIEWER',
  VIEW_RESULT: 'VIEW_RESULT',
} as const;
export type TaskAction = (typeof TaskAction)[keyof typeof TaskAction];

export const TaskListTab = {
  ALL: 'ALL',
  MINE: 'MINE',
  PENDING_APPROVAL: 'PENDING_APPROVAL',
  NEEDS_EDIT: 'NEEDS_EDIT',
  NEEDS_ATTENTION: 'NEEDS_ATTENTION',
} as const;
export type TaskListTab = (typeof TaskListTab)[keyof typeof TaskListTab];

export const TaskHistoryKind = {
  TASK_CREATED: 'TASK_CREATED',
  PLAN_SAVED: 'PLAN_SAVED',
  PLAN_SUBMITTED: 'PLAN_SUBMITTED',
  PLAN_WITHDRAWN: 'PLAN_WITHDRAWN',
  PLAN_APPROVED: 'PLAN_APPROVED',
  PLAN_CHANGES_REQUESTED: 'PLAN_CHANGES_REQUESTED',
  TASK_STARTED: 'TASK_STARTED',
  PROGRESS_NOTE: 'PROGRESS_NOTE',
  RESULT_SUBMITTED: 'RESULT_SUBMITTED',
  RESULT_ACCEPTED: 'RESULT_ACCEPTED',
  RESULT_FIX_REQUIRED: 'RESULT_FIX_REQUIRED',
  RESULT_UPDATE_REQUIRED: 'RESULT_UPDATE_REQUIRED',
  CARRIED_OVER: 'CARRIED_OVER',
  SUPPORT_HANDOFF: 'SUPPORT_HANDOFF',
  POINTS_AWARDED: 'POINTS_AWARDED',
  POINTS_REVERSED: 'POINTS_REVERSED',
} as const;
export type TaskHistoryKind = (typeof TaskHistoryKind)[keyof typeof TaskHistoryKind];

export const CheckpointStatus = {
  SCHEDULED: 'SCHEDULED',
  OPEN: 'OPEN',
  CLOSED: 'CLOSED',
  FINALIZED: 'FINALIZED',
  CANCELLED: 'CANCELLED',
} as const;
export type CheckpointStatus = (typeof CheckpointStatus)[keyof typeof CheckpointStatus];

/** Nhãn đánh giá của kỳ: Tạm tính / Chờ TL kiểm tra / Đã chốt đánh giá. */
export const CyclePhase = {
  TENTATIVE: 'TENTATIVE',
  AWAITING_LEAD_REVIEW: 'AWAITING_LEAD_REVIEW',
  FINALIZED: 'FINALIZED',
} as const;
export type CyclePhase = (typeof CyclePhase)[keyof typeof CyclePhase];

export const CheckpointReportStatus = { DRAFT: 'DRAFT', SUBMITTED: 'SUBMITTED' } as const;
export type CheckpointReportStatus = (typeof CheckpointReportStatus)[keyof typeof CheckpointReportStatus];

/** Trạng thái báo cáo của một thành viên trong kỳ (NOT_RESPONDED = chưa có bản gửi, KHÔNG phải trạng thái task). */
export const MemberReportState = {
  NOT_RESPONDED: 'NOT_RESPONDED',
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
} as const;
export type MemberReportState = (typeof MemberReportState)[keyof typeof MemberReportState];

export const CheckpointTaskResult = {
  RESULT_SUBMITTED: 'RESULT_SUBMITTED',
  RESULT_MISSING: 'RESULT_MISSING',
} as const;
export type CheckpointTaskResult = (typeof CheckpointTaskResult)[keyof typeof CheckpointTaskResult];

/** Ba lựa chọn nguy cơ ở S06: Đúng kế hoạch / Có nguy cơ trễ / Cần TL hỗ trợ. */
export const ReportRiskLevel = {
  ON_TRACK: 'ON_TRACK',
  AT_RISK: 'AT_RISK',
  NEEDS_LEAD_HELP: 'NEEDS_LEAD_HELP',
} as const;
export type ReportRiskLevel = (typeof ReportRiskLevel)[keyof typeof ReportRiskLevel];

export const DashboardColor = {
  GREEN: 'GREEN',
  YELLOW: 'YELLOW',
  RED: 'RED',
  GRAY: 'GRAY',
} as const;
export type DashboardColor = (typeof DashboardColor)[keyof typeof DashboardColor];

export const DashboardReasonCode = {
  ALL_ACCEPTED: 'ALL_ACCEPTED',
  ALL_SUBMITTED_PENDING_REVIEW: 'ALL_SUBMITTED_PENDING_REVIEW',
  LATE_1_2: 'LATE_1_2',
  LATE_3_PLUS: 'LATE_3_PLUS',
  DROPOUT_RISK_CONFIRMED: 'DROPOUT_RISK_CONFIRMED',
  NO_TASK_DUE: 'NO_TASK_DUE',
  INSUFFICIENT_DATA: 'INSUFFICIENT_DATA',
} as const;
export type DashboardReasonCode = (typeof DashboardReasonCode)[keyof typeof DashboardReasonCode];

export const GroupHealth = {
  ON_TRACK: 'ON_TRACK',
  NEEDS_ATTENTION: 'NEEDS_ATTENTION',
  INSUFFICIENT_INFO: 'INSUFFICIENT_INFO',
} as const;
export type GroupHealth = (typeof GroupHealth)[keyof typeof GroupHealth];

export const ActionKind = {
  CHECKPOINT_REPORT: 'CHECKPOINT_REPORT',
  COMPLETE_PLAN: 'COMPLETE_PLAN',
  REVIEW_PLAN: 'REVIEW_PLAN',
  REVIEW_RESULT: 'REVIEW_RESULT',
  SET_CARRYOVER: 'SET_CARRYOVER',
  FIX_TASK: 'FIX_TASK',
  REVIEW_SUPPORT_REQUEST: 'REVIEW_SUPPORT_REQUEST',
  REVIEW_SUPPORT_OFFERS: 'REVIEW_SUPPORT_OFFERS',
  REVIEW_SUPPORT_RESULT: 'REVIEW_SUPPORT_RESULT',
  CONTINUE_SUPPORT_WORK: 'CONTINUE_SUPPORT_WORK',
} as const;
export type ActionKind = (typeof ActionKind)[keyof typeof ActionKind];

export const SupportRequestStatus = {
  OPEN: 'OPEN',
  IN_REVIEW: 'IN_REVIEW',
  RESOLVED: 'RESOLVED',
} as const;
export type SupportRequestStatus = (typeof SupportRequestStatus)[keyof typeof SupportRequestStatus];

export const SupportRequestDecision = {
  RESOLVED_PRIVATELY: 'RESOLVED_PRIVATELY',
  HELP_PUBLISHED: 'HELP_PUBLISHED',
} as const;
export type SupportRequestDecision = (typeof SupportRequestDecision)[keyof typeof SupportRequestDecision];

/** Hành động TL gửi qua POST /support-requests/{id}/decision. */
export const SupportRequestLeadAction = {
  RESOLVED_PRIVATELY: 'RESOLVED_PRIVATELY',
  NEED_MORE_DISCUSSION: 'NEED_MORE_DISCUSSION',
} as const;
export type SupportRequestLeadAction = (typeof SupportRequestLeadAction)[keyof typeof SupportRequestLeadAction];

export const SupportRequestAction = { DECIDE: 'DECIDE', PUBLISH_HELP: 'PUBLISH_HELP' } as const;
export type SupportRequestAction = (typeof SupportRequestAction)[keyof typeof SupportRequestAction];

export const ObstacleType = {
  TECHNICAL: 'TECHNICAL',
  TIME: 'TIME',
  SCOPE: 'SCOPE',
  SKILL: 'SKILL',
  PERSONAL: 'PERSONAL',
  OTHER: 'OTHER',
} as const;
export type ObstacleType = (typeof ObstacleType)[keyof typeof ObstacleType];

export const RiskType = {
  DELAY_RISK: 'DELAY_RISK',
  BLOCKED: 'BLOCKED',
  DROPOUT_RISK: 'DROPOUT_RISK',
} as const;
export type RiskType = (typeof RiskType)[keyof typeof RiskType];

export const RiskReportStatus = { OPEN: 'OPEN', CONFIRMED: 'CONFIRMED', RESOLVED: 'RESOLVED' } as const;
export type RiskReportStatus = (typeof RiskReportStatus)[keyof typeof RiskReportStatus];

export const HelpRequestStatus = {
  OPEN: 'OPEN',
  MATCHED: 'MATCHED',
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED',
} as const;
export type HelpRequestStatus = (typeof HelpRequestStatus)[keyof typeof HelpRequestStatus];

export const HelpRoleInRequest = {
  REQUESTER: 'REQUESTER',
  BENEFICIARY: 'BENEFICIARY',
  HELPER: 'HELPER',
  VIEWER: 'VIEWER',
} as const;
export type HelpRoleInRequest = (typeof HelpRoleInRequest)[keyof typeof HelpRoleInRequest];

export const HelpRequestAction = {
  OFFER_HELP: 'OFFER_HELP',
  WITHDRAW_OFFER: 'WITHDRAW_OFFER',
  RECONFIRM_OFFER: 'RECONFIRM_OFFER',
  ACCEPT_OFFER: 'ACCEPT_OFFER',
  EDIT: 'EDIT',
  CANCEL: 'CANCEL',
  OPEN_WORK: 'OPEN_WORK',
} as const;
export type HelpRequestAction = (typeof HelpRequestAction)[keyof typeof HelpRequestAction];

export const HelpRequestTab = {
  OPEN: 'OPEN',
  MY_OFFERS: 'MY_OFFERS',
  MY_WORK: 'MY_WORK',
  COMPLETED: 'COMPLETED',
} as const;
export type HelpRequestTab = (typeof HelpRequestTab)[keyof typeof HelpRequestTab];

export const SupportOfferStatus = {
  PENDING: 'PENDING',
  ACCEPTED: 'ACCEPTED',
  NOT_SELECTED: 'NOT_SELECTED',
  WITHDRAWN: 'WITHDRAWN',
} as const;
export type SupportOfferStatus = (typeof SupportOfferStatus)[keyof typeof SupportOfferStatus];

export const SupportWorkStatus = {
  IN_PROGRESS: 'IN_PROGRESS',
  SUBMITTED: 'SUBMITTED',
  REVISION_REQUESTED: 'REVISION_REQUESTED',
  NEEDS_DISCUSSION: 'NEEDS_DISCUSSION',
  APPROVED: 'APPROVED',
} as const;
export type SupportWorkStatus = (typeof SupportWorkStatus)[keyof typeof SupportWorkStatus];

export const SupportWorkAction = {
  SUBMIT_RESULT: 'SUBMIT_RESULT',
  REPORT_UNABLE: 'REPORT_UNABLE',
  REVIEW_RESULT: 'REVIEW_RESULT',
  RESOLVE_DISCUSSION: 'RESOLVE_DISCUSSION',
  VIEW_CONTRIBUTION: 'VIEW_CONTRIBUTION',
} as const;
export type SupportWorkAction = (typeof SupportWorkAction)[keyof typeof SupportWorkAction];

export const SupportWorkResolution = {
  RESUME: 'RESUME',
  REOPEN_REQUEST: 'REOPEN_REQUEST',
  CANCEL_REQUEST: 'CANCEL_REQUEST',
} as const;
export type SupportWorkResolution = (typeof SupportWorkResolution)[keyof typeof SupportWorkResolution];

export const SupportTimelineKind = {
  OFFER_ACCEPTED: 'OFFER_ACCEPTED',
  RESULT_SUBMITTED: 'RESULT_SUBMITTED',
  REVISION_REQUESTED: 'REVISION_REQUESTED',
  UNABLE_REPORTED: 'UNABLE_REPORTED',
  DISCUSSION_RESOLVED: 'DISCUSSION_RESOLVED',
  APPROVED: 'APPROVED',
} as const;
export type SupportTimelineKind = (typeof SupportTimelineKind)[keyof typeof SupportTimelineKind];

export const ContributionStatus = {
  PENDING_CONFIRMATION: 'PENDING_CONFIRMATION',
  CONFIRMED: 'CONFIRMED',
} as const;
export type ContributionStatus = (typeof ContributionStatus)[keyof typeof ContributionStatus];

export const PointPolicyStatus = { DRAFT: 'DRAFT', ACTIVE: 'ACTIVE', ARCHIVED: 'ARCHIVED' } as const;
export type PointPolicyStatus = (typeof PointPolicyStatus)[keyof typeof PointPolicyStatus];

export const PointPolicyBasis = { INITIAL: 'INITIAL', ADDITIONAL: 'ADDITIONAL' } as const;
export type PointPolicyBasis = (typeof PointPolicyBasis)[keyof typeof PointPolicyBasis];

/** Chỉ dùng trong DB (task_point_budgets.state) — không xuất hiện trong response API. */
export const TaskPointBudgetState = { FUNDED: 'FUNDED', RETIRED: 'RETIRED' } as const;
export type TaskPointBudgetState = (typeof TaskPointBudgetState)[keyof typeof TaskPointBudgetState];

/** Chỉ dùng trong DB (task_point_reservations.state). */
export const PointReservationState = { HELD: 'HELD', CONSUMED: 'CONSUMED', RELEASED: 'RELEASED' } as const;
export type PointReservationState = (typeof PointReservationState)[keyof typeof PointReservationState];

/** Chỉ dùng trong DB (task_point_assignments.status). */
export const PointAssignmentStatus = { CURRENT: 'CURRENT', SUPERSEDED: 'SUPERSEDED' } as const;
export type PointAssignmentStatus = (typeof PointAssignmentStatus)[keyof typeof PointAssignmentStatus];

export const LedgerEntryType = { AWARD: 'AWARD', REVERSAL: 'REVERSAL' } as const;
export type LedgerEntryType = (typeof LedgerEntryType)[keyof typeof LedgerEntryType];

/** Trạng thái task có điểm ở S12 (đúng 6 giá trị theo spec). */
export const PointTaskStatus = {
  NOT_STARTED: 'NOT_STARTED',
  IN_PROGRESS: 'IN_PROGRESS',
  AWAITING_REVIEW: 'AWAITING_REVIEW',
  NEEDS_REVISION: 'NEEDS_REVISION',
  AWARDED: 'AWARDED',
  TRANSFERRED_OUT: 'TRANSFERRED_OUT',
} as const;
export type PointTaskStatus = (typeof PointTaskStatus)[keyof typeof PointTaskStatus];

export const PointTaskSource = { OWN: 'OWN', TAKEN_OVER: 'TAKEN_OVER' } as const;
export type PointTaskSource = (typeof PointTaskSource)[keyof typeof PointTaskSource];

export const PointHistoryGroup = {
  POINTS_RECORDED: 'POINTS_RECORDED',
  ASSIGNMENT: 'ASSIGNMENT',
} as const;
export type PointHistoryGroup = (typeof PointHistoryGroup)[keyof typeof PointHistoryGroup];

export const PointHistoryKind = {
  AWARD: 'AWARD',
  REVERSAL: 'REVERSAL',
  ASSIGNMENT_OUT: 'ASSIGNMENT_OUT',
  ASSIGNMENT_IN: 'ASSIGNMENT_IN',
  RESERVATION_HELD: 'RESERVATION_HELD',
  RESERVATION_RELEASED: 'RESERVATION_RELEASED',
} as const;
export type PointHistoryKind = (typeof PointHistoryKind)[keyof typeof PointHistoryKind];

export const SimulationStepKind = {
  START: 'START',
  TRANSFER_OUT: 'TRANSFER_OUT',
  TAKE_ON: 'TAKE_ON',
} as const;
export type SimulationStepKind = (typeof SimulationStepKind)[keyof typeof SimulationStepKind];

export const NotificationType = {
  TASK_ASSIGNED: 'TASK_ASSIGNED',
  PLAN_SUBMITTED: 'PLAN_SUBMITTED',
  PLAN_CHANGE_SUBMITTED: 'PLAN_CHANGE_SUBMITTED',
  PLAN_APPROVED: 'PLAN_APPROVED',
  PLAN_CHANGES_REQUESTED: 'PLAN_CHANGES_REQUESTED',
  CHECKPOINT_REMINDER: 'CHECKPOINT_REMINDER',
  CHECKPOINT_REVIEW_REQUIRED: 'CHECKPOINT_REVIEW_REQUIRED',
  CHECKPOINT_SCHEDULE_CHANGED: 'CHECKPOINT_SCHEDULE_CHANGED',
  NEED_HELP: 'NEED_HELP',
  SUPPORT_DECISION: 'SUPPORT_DECISION',
  HELP_PUBLISHED: 'HELP_PUBLISHED',
  RISK_RELEVANT: 'RISK_RELEVANT',
  SUPPORT_OFFERED: 'SUPPORT_OFFERED',
  SUPPORT_ACCEPTED: 'SUPPORT_ACCEPTED',
  SUPPORT_SUBMITTED: 'SUPPORT_SUBMITTED',
  REVISION_REQUESTED: 'REVISION_REQUESTED',
  CONTRIBUTION_CONFIRMED: 'CONTRIBUTION_CONFIRMED',
  POINTS_AWARDED: 'POINTS_AWARDED',
  POINT_ASSIGNMENT_CHANGED: 'POINT_ASSIGNMENT_CHANGED',
  // Mở rộng (ngoài bảng 13 của spec, xem SPEC_ISSUES.md)
  TASK_REVIEWED: 'TASK_REVIEWED',
  TASK_CARRIED_OVER: 'TASK_CARRIED_OVER',
  SUPPORT_NOT_SELECTED: 'SUPPORT_NOT_SELECTED',
  SUPPORT_WORK_ISSUE: 'SUPPORT_WORK_ISSUE',
  POINTS_ADJUSTED: 'POINTS_ADJUSTED',
  POINT_POLICY_ACTIVATED: 'POINT_POLICY_ACTIVATED',
} as const;
export type NotificationType = (typeof NotificationType)[keyof typeof NotificationType];

export const NotificationPriority = {
  ACTION_REQUIRED: 'ACTION_REQUIRED',
  RELEVANT_CHANGE: 'RELEVANT_CHANGE',
  INFORMATIONAL: 'INFORMATIONAL',
} as const;
export type NotificationPriority = (typeof NotificationPriority)[keyof typeof NotificationPriority];

export const NotificationEntityType = {
  GROUP: 'GROUP',
  TASK: 'TASK',
  PLAN_REVISION: 'PLAN_REVISION',
  CHECKPOINT_CYCLE: 'CHECKPOINT_CYCLE',
  SUPPORT_REQUEST: 'SUPPORT_REQUEST',
  HELP_REQUEST: 'HELP_REQUEST',
  SUPPORT_WORK: 'SUPPORT_WORK',
  LEDGER_ENTRY: 'LEDGER_ENTRY',
  RISK_REPORT: 'RISK_REPORT',
  POINT_POLICY: 'POINT_POLICY',
} as const;
export type NotificationEntityType = (typeof NotificationEntityType)[keyof typeof NotificationEntityType];

export const NotificationTab = { ACTION_REQUIRED: 'ACTION_REQUIRED', INFO: 'INFO', ALL: 'ALL' } as const;
export type NotificationTab = (typeof NotificationTab)[keyof typeof NotificationTab];

export const ScreenId = {
  S00: 'S00',
  S01: 'S01',
  S02: 'S02',
  S03: 'S03',
  S04: 'S04',
  S05: 'S05',
  S06: 'S06',
  S07: 'S07',
  S08: 'S08',
  S09: 'S09',
  S10: 'S10',
  S11: 'S11',
  S12: 'S12',
  S13: 'S13',
} as const;
export type ScreenId = (typeof ScreenId)[keyof typeof ScreenId];

export const EvidenceKind = { URL: 'URL' } as const;
export type EvidenceKind = (typeof EvidenceKind)[keyof typeof EvidenceKind];

export const ScheduleWarningCode = {
  TRANSITION_CYCLE_LONG: 'TRANSITION_CYCLE_LONG',
  TRANSITION_CYCLE_SHORT: 'TRANSITION_CYCLE_SHORT',
} as const;
export type ScheduleWarningCode = (typeof ScheduleWarningCode)[keyof typeof ScheduleWarningCode];

// ───────────────────────────── Mã lỗi nghiệp vụ ─────────────────────────────

export const ErrorCode = {
  // Chung
  VALIDATION_FAILED: 'VALIDATION_FAILED',
  MALFORMED_REQUEST: 'MALFORMED_REQUEST',
  IDEMPOTENCY_KEY_REQUIRED: 'IDEMPOTENCY_KEY_REQUIRED',
  IDEMPOTENCY_KEY_REUSED: 'IDEMPOTENCY_KEY_REUSED',
  IDEMPOTENCY_IN_PROGRESS: 'IDEMPOTENCY_IN_PROGRESS',
  UNAUTHENTICATED: 'UNAUTHENTICATED',
  TOKEN_EXPIRED: 'TOKEN_EXPIRED',
  INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  FORBIDDEN: 'FORBIDDEN',
  NOT_A_GROUP_MEMBER: 'NOT_A_GROUP_MEMBER',
  LEAD_ONLY: 'LEAD_ONLY',
  NOT_ASSIGNEE: 'NOT_ASSIGNEE',
  NOT_AUTHORIZED_REVIEWER: 'NOT_AUTHORIZED_REVIEWER',
  SELF_ACTION_FORBIDDEN: 'SELF_ACTION_FORBIDDEN',
  NOT_FOUND: 'NOT_FOUND',
  VERSION_CONFLICT: 'VERSION_CONFLICT',
  INVALID_STATE: 'INVALID_STATE',
  RATE_LIMITED: 'RATE_LIMITED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  // Auth / nhóm / lời mời
  EMAIL_ALREADY_REGISTERED: 'EMAIL_ALREADY_REGISTERED',
  OAUTH_NOT_CONFIGURED: 'OAUTH_NOT_CONFIGURED',
  INVITE_NOT_FOUND: 'INVITE_NOT_FOUND',
  INVITE_EXPIRED: 'INVITE_EXPIRED',
  INVITE_REVOKED: 'INVITE_REVOKED',
  ALREADY_MEMBER: 'ALREADY_MEMBER',
  GROUP_DEADLINE_INVALID: 'GROUP_DEADLINE_INVALID',
  // Lịch / kỳ
  SCHEDULE_INVALID: 'SCHEDULE_INVALID',
  // Task / kế hoạch
  ASSIGNEE_NOT_IN_GROUP: 'ASSIGNEE_NOT_IN_GROUP',
  START_AFTER_DEADLINE: 'START_AFTER_DEADLINE',
  DEADLINE_OUTSIDE_CYCLE: 'DEADLINE_OUTSIDE_CYCLE',
  DEADLINE_AFTER_GROUP_DEADLINE: 'DEADLINE_AFTER_GROUP_DEADLINE',
  PLAN_INCOMPLETE: 'PLAN_INCOMPLETE',
  PLAN_NOT_EDITABLE: 'PLAN_NOT_EDITABLE',
  INVALID_PLAN_STATE: 'INVALID_PLAN_STATE',
  REVISION_ALREADY_IN_FLIGHT: 'REVISION_ALREADY_IN_FLIGHT',
  NO_CHANGES_PROPOSED: 'NO_CHANGES_PROPOSED',
  NO_EFFECTIVE_PLAN: 'NO_EFFECTIVE_PLAN',
  PLAN_CHANGED: 'PLAN_CHANGED',
  ROUND_NOT_ACTIVE: 'ROUND_NOT_ACTIVE',
  SUBMISSION_WINDOW_CLOSED: 'SUBMISSION_WINDOW_CLOSED',
  EVIDENCE_REQUIRED: 'EVIDENCE_REQUIRED',
  REVIEW_BEFORE_CUTOFF: 'REVIEW_BEFORE_CUTOFF',
  STALE_SUBMISSION: 'STALE_SUBMISSION',
  SUBMISSION_ALREADY_REVIEWED: 'SUBMISSION_ALREADY_REVIEWED',
  INDEPENDENT_REVIEWER_REQUIRED: 'INDEPENDENT_REVIEWER_REQUIRED',
  TASK_ALREADY_DONE: 'TASK_ALREADY_DONE',
  CARRYOVER_NOT_ALLOWED: 'CARRYOVER_NOT_ALLOWED',
  CARRYOVER_AWAITING_REVIEW: 'CARRYOVER_AWAITING_REVIEW',
  CARRYOVER_ALREADY_DONE: 'CARRYOVER_ALREADY_DONE',
  CARRYOVER_DEADLINE_INVALID: 'CARRYOVER_DEADLINE_INVALID',
  CARRYOVER_REASON_REQUIRED: 'CARRYOVER_REASON_REQUIRED',
  // Báo cáo checkpoint
  REPORT_WINDOW_NOT_OPEN: 'REPORT_WINDOW_NOT_OPEN',
  CYCLE_CLOSED: 'CYCLE_CLOSED',
  REPORT_INCOMPLETE: 'REPORT_INCOMPLETE',
  RESULT_DECLARATION_MISMATCH: 'RESULT_DECLARATION_MISMATCH',
  // Hỗ trợ riêng / risk
  SUPPORT_REQUEST_CLOSED: 'SUPPORT_REQUEST_CLOSED',
  RISK_ALREADY_RESOLVED: 'RISK_ALREADY_RESOLVED',
  // Hỗ trợ công khai
  TASK_NOT_TRANSFERABLE: 'TASK_NOT_TRANSFERABLE',
  TASK_AWAITING_REVIEW: 'TASK_AWAITING_REVIEW',
  HELP_REQUEST_ALREADY_OPEN: 'HELP_REQUEST_ALREADY_OPEN',
  HELP_REQUEST_BLOCKS_REVIEW: 'HELP_REQUEST_BLOCKS_REVIEW',
  BENEFICIARY_MISMATCH: 'BENEFICIARY_MISMATCH',
  HELP_REQUEST_NOT_OPEN: 'HELP_REQUEST_NOT_OPEN',
  HELP_ALREADY_MATCHED: 'HELP_ALREADY_MATCHED',
  OFFER_ALREADY_EXISTS: 'OFFER_ALREADY_EXISTS',
  OFFER_NOT_PENDING: 'OFFER_NOT_PENDING',
  OFFER_NEEDS_RECONFIRMATION: 'OFFER_NEEDS_RECONFIRMATION',
  HELP_REQUEST_HAS_HELPER: 'HELP_REQUEST_HAS_HELPER',
  WORK_NOT_SUBMITTABLE: 'WORK_NOT_SUBMITTABLE',
  WORK_INVALID_STATE: 'WORK_INVALID_STATE',
  // Điểm
  POINT_POLICY_NOT_ACTIVE: 'POINT_POLICY_NOT_ACTIVE',
  POINT_POLICY_INVALID: 'POINT_POLICY_INVALID',
  POLICY_TASKS_NOT_APPROVED: 'POLICY_TASKS_NOT_APPROVED',
  SIMULATION_INVALID_TASK: 'SIMULATION_INVALID_TASK',
  SETTLEMENT_ALREADY_REVOKED: 'SETTLEMENT_ALREADY_REVOKED',
} as const;
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

// ───────────────────────────── Dạng chung ─────────────────────────────

export interface FieldError {
  /** Đường dẫn trường, vd "deadline", "items[2].note", "evidence[0].url". */
  field: string;
  message: string;
}

/** Mọi response lỗi (HTTP 4xx/5xx) đều có đúng dạng này. `fieldErrors` luôn có mặt (mảng rỗng nếu không có). */
export interface ApiError {
  code: ErrorCode;
  message: string;
  fieldErrors: FieldError[];
  traceId: string;
}

export interface Page<T> {
  items: T[];
  /** Trang hiện tại, bắt đầu từ 0. */
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface PageQuery {
  /** Mặc định 0. */
  page?: number;
  /** Mặc định 20, tối đa 100. */
  size?: number;
}

/** Danh sách không phân trang (nhỏ, bị chặn bởi nghiệp vụ). */
export interface ItemsResponse<T> {
  items: T[];
}

/** Mô tả một endpoint trong bản đồ `ApiEndpoints`. `undefined` = không có phần đó. */
export interface EndpointDef<P, Q, B, R> {
  params: P;
  query: Q;
  body: B;
  response: R;
}

/** Tham chiếu người dùng (không bao giờ kèm email). */
export interface UserRef {
  userId: Uuid;
  name: string;
}

export interface TaskRef {
  id: Uuid;
  title: string;
}

/** Tham chiếu kỳ checkpoint, dùng nhúng trong các đối tượng khác. */
export interface CycleRef {
  id: Uuid;
  sequenceNo: number;
  /** Cận dưới LOẠI TRỪ của kỳ (= closesAt của kỳ trước). */
  periodStartAt: IsoInstant;
  /** Cận trên BAO GỒM của kỳ (giờ chốt). */
  closesAt: IsoInstant;
  /** Vd "Kỳ 3 · 13/11 22:00 → 20/11 22:00" (theo múi giờ nhóm). */
  label: string;
}

/** Mục tiêu điều hướng (thông báo, CTA do server gợi ý). `params` là các id cần để mở màn hình. */
export interface ScreenTarget {
  screen: ScreenId;
  /** Khóa có thể gặp: groupId, taskId, planId, cycleId, supportRequestId, helpRequestId, workId, ledgerEntryId, riskReportId, tab. */
  params: Record<string, string>;
}

/** Minh chứng; khớp phần tử `evidence jsonb` ở DB: `{"type":"URL","url":"…","label":"…"}`. MVP chỉ có `type = "URL"`. */
export interface EvidenceItem {
  type: EvidenceKind;
  /** https URL, tối đa 2048 ký tự. */
  url: string;
  label?: string | null;
}

export interface ReasonRef {
  code: ErrorCode;
  message: string;
}

// ═════════════════════════════ AUTH (BE-A) ═════════════════════════════

export interface RegisterRequest {
  /** 1–80 ký tự. */
  name: string;
  /** Email hợp lệ, ≤254, không phân biệt hoa/thường (server chuẩn hóa). */
  email: string;
  /** 8–72 ký tự. */
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface GoogleLoginRequest {
  /** ID token Google. Server trả 501 OAUTH_NOT_CONFIGURED nếu chưa cấu hình. */
  idToken: string;
}

export interface UserProfile {
  userId: Uuid;
  name: string;
  email: string;
  createdAt: IsoInstant;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  /** Số giây còn hiệu lực. */
  expiresIn: number;
  expiresAt: IsoInstant;
  user: UserProfile;
}

export interface AuthConfigResponse {
  /** false → FE ẩn/disable nút "Tiếp tục với Google" kèm tooltip. */
  googleEnabled: boolean;
  passwordMinLength: number;
  /** true khi chạy profile `demo` (FE có thể gợi ý tài khoản demo qua GET /demo/accounts). */
  demoMode: boolean;
}

// ═════════════════════════════ GROUP / MEMBER / INVITE (BE-A) ═════════════════════════════

export interface GroupCardAttention {
  count: number;
  /** "Không có việc cần chú ý" | "1 checkpoint cần phản hồi" | "Có 1 việc liên quan đến bạn". */
  label: string;
}

export interface GroupCard {
  id: Uuid;
  name: string;
  courseName: string | null;
  memberCount: number;
  /** Deadline chung của nhóm (ngày, theo múi giờ nhóm). */
  deadline: IsoDate;
  myRole: GroupRole;
  attention: GroupCardAttention;
  unreadNotifications: number;
  lastActivityAt: IsoInstant | null;
}

export interface Group {
  id: Uuid;
  name: string;
  courseName: string | null;
  startDate: IsoDate;
  deadline: IsoDate;
  /** IANA, vd "Asia/Ho_Chi_Minh". Mọi giờ hiển thị cho nhóm dùng múi giờ này. */
  timezone: string;
  memberCount: number;
  leader: UserRef;
  myRole: GroupRole;
  createdAt: IsoInstant;
  version: number;
}

export interface ScheduleInput {
  /** ISO weekday: 1 = Thứ Hai … 5 = Thứ Sáu … 7 = Chủ nhật. */
  weekday: number;
  /** Giờ chốt địa phương "HH:mm". */
  cutoffTime: LocalTime;
  /** IANA timezone. */
  timezone: string;
  /** null = mở báo cáo cả kỳ; số = khung mở (phút) tính lùi từ giờ chốt, 60–20160. */
  reportingWindowMinutes?: number | null;
}

export interface CreateGroupRequest {
  /** 3–80 ký tự. */
  name: string;
  courseName?: string | null;
  /** Mặc định hôm nay (theo múi giờ lịch). */
  startDate?: IsoDate | null;
  /** Không trước startDate. */
  deadline: IsoDate;
  schedule: ScheduleInput;
  /** TL xác nhận đây là lịch nhóm đã thống nhất; phải là true. */
  scheduleAgreed: true;
}

export interface CreateGroupResponse {
  group: Group;
  schedule: CheckpointSchedule;
  currentCycle: CheckpointCycle;
  invite: Invite;
}

export interface UpdateGroupRequest {
  name?: string;
  courseName?: string | null;
  deadline?: IsoDate;
  version: number;
}

export interface Member {
  userId: Uuid;
  name: string;
  role: GroupRole;
  joinedAt: IsoInstant;
}

export interface Invite {
  /** Mã mời (8–12 ký tự chữ-số, không phân biệt hoa/thường). */
  code: string;
  status: InviteStatus;
  /** Đường dẫn chia sẻ tương đối, vd "/join/K7P4Q9XD". */
  sharePath: string;
  createdBy: UserRef;
  createdAt: IsoInstant;
  expiresAt: IsoInstant | null;
}

export interface InvitePreview {
  code: string;
  status: InviteStatus;
  groupName: string;
  courseName: string | null;
  leader: UserRef;
  memberCount: number;
  expiresAt: IsoInstant | null;
  /** null nếu gọi không đăng nhập. */
  alreadyMember: boolean | null;
  /** Chỉ có giá trị khi alreadyMember = true (để "Đi tới nhóm"). */
  groupId: Uuid | null;
}

export interface AcceptInviteResponse {
  group: Group;
}

// ═════════════════════════════ SCHEDULE & CYCLES (BE-A) ═════════════════════════════

export interface CheckpointSchedule {
  id: Uuid;
  versionNo: number;
  weekday: number;
  cutoffTime: LocalTime;
  timezone: string;
  /** Luôn 420 (nhắc trước 7 giờ) ở MVP. */
  reminderBeforeMinutes: number;
  reportingWindowMinutes: number | null;
  /** Lịch áp dụng cho các kỳ có periodStartAt >= effectiveFrom (= giờ chốt của kỳ cuối còn giữ lịch cũ). */
  effectiveFrom: IsoInstant;
  agreedAt: IsoInstant;
  recordedBy: UserRef;
  isCurrent: boolean;
  /** Vd "Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00". */
  summaryLabel: string;
}

export interface CheckpointCycle {
  id: Uuid;
  groupId: Uuid;
  scheduleId: Uuid;
  sequenceNo: number;
  /** Cận dưới LOẠI TRỪ: kỳ là (periodStartAt, closesAt]. */
  periodStartAt: IsoInstant;
  /** Khung mở form báo cáo (>= periodStartAt). */
  opensAt: IsoInstant;
  /** closesAt − 7 giờ. */
  reminderAt: IsoInstant;
  /** Giờ chốt (bao gồm). */
  closesAt: IsoInstant;
  status: CheckpointStatus;
  phase: CyclePhase;
  /** "Tạm tính" | "Chờ TL kiểm tra" | "Đã chốt đánh giá". */
  phaseLabel: string;
  finalizedAt: IsoInstant | null;
  /** Kỳ chứa thời điểm hiện tại: periodStartAt < now <= closesAt. */
  isCurrent: boolean;
  /** true khi now ∈ [opensAt, closesAt] (member được lưu/gửi báo cáo). */
  reportWindowOpen: boolean;
  timezone: string;
  /** Vd "Kỳ 3 · 13/11 22:00 → 20/11 22:00". */
  label: string;
}

export interface CheckpointCycleDetail extends CheckpointCycle {
  serverTime: IsoInstant;
  previousCycleId: Uuid | null;
  nextCycleId: Uuid | null;
  activeMemberCount: number;
  reportedMemberCount: number;
  notRespondedMemberCount: number;
  /** Chỉ TL nhận; Member nhận null. */
  leadSummary: LeadCycleSummary | null;
}

export interface ListCyclesQuery extends PageQuery {
  status?: CheckpointStatus;
  /** Mặc định desc (kỳ mới nhất trước). */
  sort?: 'asc' | 'desc';
}

export interface SchedulePreviewRequest {
  /** Có = xem trước thay đổi lịch của nhóm đó (TL); không có = xem trước cho nhóm sắp tạo (S02). */
  groupId?: Uuid | null;
  /** Chỉ dùng khi không có groupId. Mặc định hôm nay. */
  startDate?: IsoDate | null;
  weekday: number;
  cutoffTime: LocalTime;
  timezone: string;
  reportingWindowMinutes?: number | null;
}

export interface SchedulePreviewCycle {
  sequenceNo: number;
  periodStartAt: IsoInstant;
  opensAt: IsoInstant;
  reminderAt: IsoInstant;
  closesAt: IsoInstant;
  /** Độ dài kỳ tính bằng giờ; khác 168 = kỳ chuyển tiếp. */
  periodHours: number;
  isTransition: boolean;
  label: string;
}

export interface ScheduleWarning {
  code: ScheduleWarningCode;
  message: string;
}

export interface SchedulePreviewResponse {
  /** Vd "Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00". */
  summaryLabel: string;
  reminderBeforeMinutes: number;
  /** 4 kỳ tới. */
  cycles: SchedulePreviewCycle[];
  warnings: ScheduleWarning[];
}

export interface SaveScheduleRequest extends ScheduleInput {
  /** TL xác nhận lịch đã thống nhất trong nhóm; phải là true. */
  scheduleAgreed: true;
}

export interface SaveScheduleResponse {
  schedule: CheckpointSchedule;
  /** Các kỳ tương lai chưa mở bị ảnh hưởng (đã sinh lại). */
  affectedCycles: CheckpointCycle[];
}

/** CURRENT = số liệu hiện tại (mặc định); AT_CUTOFF = bản đóng băng tại giờ chốt (không đổi sau khi chốt). */
export type LeadSummaryView = 'CURRENT' | 'AT_CUTOFF';

/** Tóm tắt dành cho TL (CHECKPOINT_REVIEW_REQUIRED + khối TL ở S03). */
export interface LeadCycleSummary {
  cycleId: Uuid;
  phase: CyclePhase;
  /** Thời điểm tính (server). */
  asOf: IsoInstant;
  /** true = số liệu đóng băng tại giờ chốt; false = số liệu hiện tại. */
  isAtCutoff: boolean;
  members: { total: number; reported: number; notResponded: number };
  tasks: {
    total: number;
    accepted: number;
    submittedPendingReview: number;
    late: number;
    needsRework: number;
    noResult: number;
    carryoverRequired: number;
  };
  openRisks: number;
  openPrivateSupportRequests: number;
  openHelpRequests: number;
  /** Nhãn trung lập "Chưa có cập nhật" — KHÔNG phải trễ/bỏ việc. */
  notRespondedMembers: UserRef[];
  headline: string;
}

// ═════════════════════════════ DASHBOARD (BE-A) ═════════════════════════════

export interface GroupHeader {
  id: Uuid;
  name: string;
  courseName: string | null;
  deadline: IsoDate;
  memberCount: number;
  leader: UserRef;
  health: GroupHealth;
  /** "Theo kế hoạch" | "Cần xem xét" | "Chưa đủ thông tin". */
  healthLabel: string;
  healthReason: string;
}

export interface ActionItem {
  kind: ActionKind;
  title: string;
  subtitle: string | null;
  dueAt: IsoInstant | null;
  count: number | null;
  target: ScreenTarget;
}

export interface NextCheckpointCard {
  cycle: CheckpointCycle;
  /** Số task bắt buộc báo cáo của tôi trong kỳ. */
  myRequiredTaskCount: number;
  myRespondedTaskCount: number;
  myReportState: MemberReportState;
  /** "Chưa có cập nhật" | "Đã lưu nháp" | "Đã gửi đầy đủ". */
  myReportLabel: string;
  canReportNow: boolean;
  reportedMemberCount: number;
  notRespondedMemberCount: number;
}

export interface MemberCycleCounts {
  /** Mọi task thuộc kỳ (kể cả chưa đến hạn). */
  totalTasks: number;
  /** Task có deadline <= now (hoặc kỳ đã đóng). */
  dueTasks: number;
  acceptedTasks: number;
  pendingReviewTasks: number;
  lateTasks: number;
  needsReworkTasks: number;
  noResultTasks: number;
  reportedTasks: number;
  missingReportTasks: number;
}

export interface MemberCycleEvaluation {
  color: DashboardColor;
  /** "Xanh" | "Vàng" | "Đỏ" | "Xám". */
  colorLabel: string;
  /** Vd "Đã nộp đủ — chờ TL duyệt", "Trễ 2 task". */
  statusLabel: string;
  reasonCode: DashboardReasonCode;
  /** Lý do chi tiết, kèm số lượng; không suy ra bỏ việc từ im lặng. */
  reason: string;
  /** true = màu tạm tính (còn chờ duyệt/kỳ chưa chốt). */
  provisional: boolean;
  phase: CyclePhase;
  phaseLabel: string;
  counts: MemberCycleCounts;
}

export interface DropoutRiskInfo {
  riskReportId: Uuid;
  confirmedBy: UserRef;
  confirmedAt: IsoInstant;
  publicSummary: string;
}

export interface MemberProgress {
  member: UserRef;
  role: GroupRole;
  /** Đánh giá hiện tại (Tạm tính / Sau TL kiểm tra). */
  evaluation: MemberCycleEvaluation;
  /** Đánh giá đóng băng tại giờ chốt; null nếu kỳ chưa đóng. */
  cutoffEvaluation: MemberCycleEvaluation | null;
  reportState: MemberReportState;
  reportLabel: string;
  reportSubmittedAt: IsoInstant | null;
  dropoutRisk: DropoutRiskInfo | null;
}

export interface PlanQueueItem {
  planId: Uuid;
  taskId: Uuid;
  taskTitle: string;
  assignee: UserRef;
  submittedAt: IsoInstant;
  /** true = đề xuất thay đổi kế hoạch đã duyệt. */
  isChange: boolean;
}

export interface LeadPanel {
  pendingPlans: { count: number; items: PlanQueueItem[] };
  privateSupportRequests: { count: number; items: SupportRequestSummary[] };
  /** Kỳ đang cần TL kiểm tra (đã chốt, chưa FINALIZED); null nếu không có. */
  reviewCycle: LeadCycleSummary | null;
  awaitingReviewCount: number;
  carryoverRequiredCount: number;
}

export interface SharedRiskCard {
  id: Uuid;
  riskType: RiskType;
  taskId: Uuid | null;
  taskTitle: string | null;
  publicSummary: string;
  createdAt: IsoInstant;
}

export interface DashboardPoints {
  policyActive: boolean;
  /** null khi chưa có phương án điểm. */
  confirmedPoints: PointAmount | null;
  potentialPoints: PointAmount | null;
  /** Vd "Đã xác nhận 10,0000% / Có thể đạt 14,0000%". */
  label: string | null;
}

export interface ActivityItem {
  id: Uuid;
  at: IsoInstant;
  text: string;
  target: ScreenTarget | null;
}

export interface DashboardResponse {
  serverTime: IsoInstant;
  group: GroupHeader;
  myRole: GroupRole;
  /** Kỳ đang xem (mặc định kỳ hiện tại). */
  cycle: CheckpointCycle;
  previousCycleId: Uuid | null;
  nextCycleId: Uuid | null;
  /** Chỉ chứa CTA có thật. */
  myActions: ActionItem[];
  /** 2–5 task sắp tới của tôi. */
  myTasks: TaskSummary[];
  nextCheckpoint: NextCheckpointCard | null;
  memberProgress: MemberProgress[];
  /** null với Member. */
  leadPanel: LeadPanel | null;
  sharedRisks: SharedRiskCard[];
  /** 1–3 yêu cầu hỗ trợ đang mở. */
  openHelp: HelpRequestCard[];
  myPoints: DashboardPoints;
  /** Tối đa 5. */
  recentActivity: ActivityItem[];
  flags: { groupHasAnyTask: boolean; iHaveAnyTask: boolean };
}

/** Một dòng task trong một kỳ (dựng từ snapshot/vòng xử lý, không dựng lại từ trạng thái hiện tại). */
export interface CycleTaskRow {
  taskId: Uuid;
  roundId: Uuid;
  roundNo: number;
  title: string;
  /** Người thực hiện trong kỳ đó (theo snapshot nếu kỳ đã chốt). */
  assignee: UserRef;
  /** Chỉ có khi task đã được chuyển cho Helper. */
  handedOverTo: UserRef | null;
  deadline: IsoInstant;
  difficulty: Difficulty | null;
  taskStatus: TaskStatus;
  timeliness: TaskTimeliness;
  /** Vd "Nộp đúng hạn — chờ TL duyệt". */
  timelinessLabel: string;
  /** Có được đếm là trễ trong kỳ này không. */
  isLate: boolean;
  submittedAt: IsoInstant | null;
  review: {
    decision: TaskReviewDecision;
    reviewer: UserRef;
    reviewedAt: IsoInstant;
    issueDescription: string | null;
  } | null;
  /** = submittedAt của bản được chấp nhận. */
  completedAt: IsoInstant | null;
  /** = reviewedAt của bản được chấp nhận (riêng, không dùng để phạt). */
  acceptedAt: IsoInstant | null;
  completedLate: boolean;
  reported: boolean;
  declaredResult: CheckpointTaskResult | null;
  riskLevel: ReportRiskLevel | null;
  /** Chỉ TL thấy; Member khác nhận null. */
  reportNote: string | null;
  carryover: { required: boolean; reason: RoundReason | null; targetRoundId: Uuid | null };
}

export interface MemberProgressDetail {
  cycle: CheckpointCycle;
  progress: MemberProgress;
  tasks: CycleTaskRow[];
}

// ═════════════════════════════ TASKS (BE-B) ═════════════════════════════

export interface TaskPointsBrief {
  /** P_t đã khóa. */
  amount: PointAmount;
  status: PointTaskStatus;
  /** Người phụ trách gốc (nguồn quỹ). */
  originalAssignee: UserRef;
  /** Người đang có quyền nhận P_t. */
  currentRecipient: UserRef;
  /** true = quỹ P_t đang được giữ cho yêu cầu hỗ trợ mở ("Đang chờ chuyển"). */
  heldForHelpRequest: boolean;
  /** true = đã có khoản AWARD chưa bị đảo. */
  awarded: boolean;
}

export interface TaskSummary {
  id: Uuid;
  groupId: Uuid;
  title: string;
  difficulty: Difficulty | null;
  originalAssignee: UserRef;
  currentExecutor: UserRef;
  /** Kỳ của vòng hiện tại (kỳ đề xuất nếu chưa được duyệt). */
  cycle: CycleRef;
  roundId: Uuid;
  roundNo: number;
  startAt: IsoInstant | null;
  /** Deadline của bản có hiệu lực; chưa duyệt thì là deadline dự kiến (committed=false). */
  deadline: IsoInstant | null;
  /** Deadline gốc (vòng 1); null nếu chưa duyệt lần nào. */
  originalDeadline: IsoInstant | null;
  /** true = đã có bản kế hoạch APPROVED (cam kết chính thức). */
  committed: boolean;
  /** Nhãn "Trạng thái kế hoạch": bản có hiệu lực → APPROVED, chưa có thì trạng thái bản đang soạn/chờ. */
  planStatus: PlanStatus;
  /** Bản sửa đang soạn/chờ duyệt song song với bản có hiệu lực; null nếu không có. */
  pendingRevisionStatus: PlanStatus | null;
  pendingRevisionId: Uuid | null;
  /** Nhãn "Trạng thái thực hiện" (server dựng từ vòng + lần nộp + quyết định). */
  taskStatus: TaskStatus;
  riskStatus: RiskStatus;
  timeliness: TaskTimeliness;
  timelinessLabel: string;
  isLate: boolean;
  /** Cần TL chốt deadline xử lý tiếp. */
  needsCarryover: boolean;
  carryoverReason: RoundReason | null;
  /** Lần nộp mới nhất của vòng hiện tại. */
  latestSubmittedAt: IsoInstant | null;
  /** null khi chưa có phương án điểm hoặc task chưa được định giá. */
  points: TaskPointsBrief | null;
  hasOpenHelpRequest: boolean;
  version: number;
  /** Hành động người gọi được phép làm lúc này (server tính theo vai trò + trạng thái). */
  allowedActions: TaskAction[];
}

export interface PlanFieldChange {
  field: 'title' | 'description' | 'expectedOutput' | 'cycleId' | 'assigneeId' | 'startAt' | 'deadline' | 'difficulty';
  oldValue: string | null;
  newValue: string | null;
  /** Nhãn hiển thị (tên người, nhãn kỳ, "Khó"…). */
  oldLabel: string | null;
  newLabel: string | null;
}

export interface PlanRevision {
  id: Uuid;
  taskId: Uuid;
  roundId: Uuid;
  revisionNo: number;
  baseRevisionId: Uuid | null;
  status: PlanStatus;
  proposedCycle: CycleRef;
  assignee: UserRef;
  title: string;
  /** Phạm vi công việc. */
  description: string | null;
  expectedOutput: string | null;
  startAt: IsoInstant | null;
  deadline: IsoInstant | null;
  difficulty: Difficulty | null;
  approvalSource: PlanApprovalSource;
  /** Lý do thay đổi (bản sửa sau khi đã duyệt). */
  changeReason: string | null;
  submittedBy: UserRef | null;
  submittedAt: IsoInstant | null;
  reviewedBy: UserRef | null;
  reviewedAt: IsoInstant | null;
  reviewNote: string | null;
  /** So sánh cũ/mới với bản nền (rỗng nếu không có bản nền). */
  changes: PlanFieldChange[];
  isEffective: boolean;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  version: number;
}

export interface CarryoverRecord {
  id: Uuid;
  sourceRoundId: Uuid;
  targetRoundId: Uuid;
  decidedBy: UserRef;
  decisionReason: string | null;
  decidedAt: IsoInstant;
}

export interface TaskRound {
  id: Uuid;
  taskId: Uuid;
  roundNo: number;
  reason: RoundReason;
  state: RoundState;
  cycle: CycleRef;
  previousRoundId: Uuid | null;
  effectivePlanId: Uuid | null;
  /** Deadline của bản có hiệu lực của vòng này. */
  deadline: IsoInstant | null;
  /** Quyết định TL trên bản nộp chốt của vòng; null = chưa quyết/không có bản nộp. */
  decision: TaskReviewDecision | null;
  startedAt: IsoInstant | null;
  closedAt: IsoInstant | null;
  carryover: CarryoverRecord | null;
}

export interface TaskResultReview {
  id: Uuid;
  submissionId: Uuid;
  decision: TaskReviewDecision;
  reviewer: UserRef;
  issueDescription: string | null;
  reviewedAt: IsoInstant;
}

export interface TaskSubmission {
  id: Uuid;
  roundId: Uuid;
  taskId: Uuid;
  planRevisionId: Uuid;
  submissionNo: number;
  submittedBy: UserRef;
  resultNote: string | null;
  evidence: EvidenceItem[];
  /** Do server cấp (không nhận từ FE). */
  submittedAt: IsoInstant;
  /** Deadline của bản kế hoạch mà lần nộp này gắn vào. */
  deadline: IsoInstant;
  /** submittedAt <= deadline (server tính). */
  isOnTime: boolean;
  /** true = đây là bản mới nhất của vòng, tức bản TL sẽ xét sau giờ chốt. */
  isReviewTarget: boolean;
  review: TaskResultReview | null;
  /** Có khi bản nộp do Helper gửi qua S10. */
  supportSubmissionId: Uuid | null;
}

export interface ProgressNote {
  id: Uuid;
  taskId: Uuid;
  by: UserRef;
  note: string;
  riskStatus: RiskStatus | null;
  expectedCompletionAt: IsoInstant | null;
  createdAt: IsoInstant;
}

export interface TaskDetail extends TaskSummary {
  /** Phạm vi (từ bản hiệu lực, hoặc bản nháp nếu chưa duyệt). */
  description: string | null;
  expectedOutput: string | null;
  /** Bản kế hoạch có hiệu lực của vòng hiện tại. */
  effectivePlan: PlanRevision | null;
  /** Bản sửa/nháp đang soạn hoặc chờ duyệt. */
  pendingRevision: PlanRevision | null;
  currentRound: TaskRound;
  /** Mọi vòng, vòng mới nhất cuối. */
  rounds: TaskRound[];
  /** Các lần nộp của vòng hiện tại (cũ → mới). */
  submissions: TaskSubmission[];
  acceptedSubmissionId: Uuid | null;
  /** = submittedAt của bản được chấp nhận. */
  completedAt: IsoInstant | null;
  /** = reviewedAt của bản được chấp nhận. */
  acceptedAt: IsoInstant | null;
  completedLate: boolean;
  /** Người duyệt độc lập đã chỉ định (chỉ dùng khi người thực hiện là TL). */
  independentReviewer: UserRef | null;
  lastProgress: ProgressNote | null;
  helpRequestId: Uuid | null;
  /** Hiện tình trạng báo cáo checkpoint của tôi cho kỳ của vòng; null nếu không áp dụng. */
  checkpoint: { cycle: CycleRef; myReportState: MemberReportState; reportWindowOpen: boolean } | null;
}

export interface ListTasksQuery extends PageQuery {
  tab?: TaskListTab;
  cycleId?: Uuid;
  /** Lọc theo người thực hiện hiện tại. */
  assigneeId?: Uuid;
  planStatus?: PlanStatus;
  taskStatus?: TaskStatus;
  difficulty?: Difficulty;
  deadlineFrom?: IsoInstant;
  deadlineTo?: IsoInstant;
  q?: string;
}

export interface CreateTaskRequest {
  /** 3–120 ký tự. */
  title: string;
  assigneeId: Uuid;
  /** Kỳ ("tuần") của task. */
  cycleId: Uuid;
  /** Phạm vi, 1–4000 ký tự. */
  description: string;
  /** Đầu ra, 1–2000 ký tự. */
  expectedOutput: string;
  startAt: IsoInstant;
  deadline: IsoInstant;
  difficulty: Difficulty;
}

export interface UpdatePlanRequest {
  title?: string;
  description?: string;
  expectedOutput?: string;
  cycleId?: Uuid;
  /** Chỉ TL được đổi người phụ trách trên bản DRAFT/CHANGES_REQUESTED chưa có bản duyệt. */
  assigneeId?: Uuid;
  startAt?: IsoInstant;
  deadline?: IsoInstant;
  difficulty?: Difficulty;
  version: number;
}

export interface ProposeChangeRequest {
  /** 5–1000 ký tự. */
  changeReason: string;
  title?: string;
  description?: string;
  expectedOutput?: string;
  cycleId?: Uuid;
  assigneeId?: Uuid;
  startAt?: IsoInstant;
  deadline?: IsoInstant;
  difficulty?: Difficulty;
  /** true = tạo xong gửi TL luôn. */
  submitNow?: boolean;
}

export interface VersionedRequest {
  version: number;
}

export interface ReviewPlanRequest {
  decision: typeof PlanStatus.APPROVED | typeof PlanStatus.CHANGES_REQUESTED;
  /** Bắt buộc (5–1000) khi CHANGES_REQUESTED. */
  note?: string | null;
  version: number;
}

export interface ReviewPlanResponse {
  plan: PlanRevision;
  task: TaskSummary;
}

export interface PlanMutationResponse {
  plan: PlanRevision;
  task: TaskSummary;
}

export interface ProgressUpdateRequest {
  /** 1–1000 ký tự. */
  note: string;
  riskStatus?: RiskStatus;
  /** Chỉ thông tin; KHÔNG đổi deadline. */
  expectedCompletionAt?: IsoInstant | null;
}

export interface SetIndependentReviewerRequest {
  /** null = bỏ chỉ định. Phải là thành viên đang hoạt động, khác người thực hiện. */
  reviewerUserId: Uuid | null;
}

export interface TaskHistoryEntry {
  id: Uuid;
  at: IsoInstant;
  actor: UserRef | null;
  kind: TaskHistoryKind;
  roundNo: number | null;
  summary: string;
  refId: Uuid | null;
}

export interface SubmitResultRequest {
  /** Bản kế hoạch có hiệu lực mà FE đang thấy; lệch → 409 PLAN_CHANGED. */
  planRevisionId: Uuid;
  /** ≤2000 ký tự. */
  resultNote?: string | null;
  /** 1–5 minh chứng. */
  evidence: EvidenceItem[];
}

export interface SubmitResultResponse {
  submission: TaskSubmission;
  task: TaskSummary;
}

export interface ReviewResultRequest {
  decision: TaskReviewDecision;
  /** Bắt buộc 10–2000 ký tự khi FIX_REQUIRED / UPDATE_REQUIRED. */
  issueDescription?: string | null;
}

export interface PointAward {
  ledgerEntryId: Uuid;
  settlementId: Uuid;
  recipient: UserRef;
  taskId: Uuid;
  points: PointDelta;
  confirmedBefore: PointAmount;
  confirmedAfter: PointAmount;
  source: PointTaskSource;
}

export interface ReviewResultResponse {
  review: TaskResultReview;
  submission: TaskSubmission;
  task: TaskSummary;
  /** Có khi ACCEPTED trên task có điểm (đúng một khoản). */
  award: PointAward | null;
  /** Có khi bản nộp thuộc một SupportWork (work/contribution/request đã đổi trạng thái). */
  supportWork: SupportWorkSummary | null;
}

export interface AwardPreview {
  recipient: UserRef;
  originalAssignee: UserRef;
  taskTitle: string;
  points: PointDelta;
  confirmedBefore: PointAmount;
  confirmedAfter: PointAmount;
  /** Vd "+10,0000 điểm % cho B từ task T của A". */
  scoreLabel: string;
  /** Vd "A không nhận điểm task T; B nhận toàn bộ quỹ T". */
  effectLabel: string;
  /** "Điểm chỉ được ghi sau khi kết quả được chấp nhận". */
  notice: string;
}

export interface ReviewPreviewResponse {
  submission: TaskSubmission;
  task: TaskSummary;
  cycle: CycleRef;
  /** Giờ chốt của kỳ chứa vòng; chỉ được duyệt sau thời điểm này. */
  cutoffAt: IsoInstant;
  canReview: boolean;
  cannotReviewReason: ReasonRef | null;
  /** Người có quyền duyệt bản nộp này. */
  authorizedReviewer: UserRef | null;
  alreadyReviewed: boolean;
  /** null nếu task không có điểm. */
  awardPreview: AwardPreview | null;
  supportWork: SupportWorkSummary | null;
}

export interface CarryoverRequest {
  /** Kỳ kế tiếp (hoặc kỳ tương lai còn phù hợp). */
  targetCycleId: Uuid;
  /** Phải ở tương lai và trong (periodStartAt, closesAt] của kỳ đích. */
  deadline: IsoInstant;
  startAt?: IsoInstant | null;
  /** Phạm vi sửa/cập nhật (≤2000); mặc định dùng mô tả lỗi của TL. */
  scopeNote?: string | null;
  /** ≤1000; BẮT BUỘC (≥5) nếu kỳ đích không phải kỳ liền sau. */
  decisionReason?: string | null;
}

export interface CarryoverResponse {
  sourceRound: TaskRound;
  targetRound: TaskRound;
  plan: PlanRevision;
  task: TaskDetail;
}

export interface ReviewQueueItem {
  row: CycleTaskRow;
  /** Bản nộp chốt cần duyệt (null nếu chưa nộp). */
  submission: TaskSubmission | null;
  canReviewNow: boolean;
}

export interface ReviewQueueResponse {
  cycle: CheckpointCycle;
  serverTime: IsoInstant;
  /** now > closesAt. */
  canReviewNow: boolean;
  awaitingReview: ReviewQueueItem[];
  carryoverRequired: ReviewQueueItem[];
  reviewed: ReviewQueueItem[];
}

// ───────────── Báo cáo checkpoint (BE-B) ─────────────

export interface SubmissionBrief {
  id: Uuid;
  submissionNo: number;
  submittedAt: IsoInstant;
  isOnTime: boolean;
  evidence: EvidenceItem[];
  reviewDecision: TaskReviewDecision | null;
}

export interface ReportItem {
  taskId: Uuid;
  roundId: Uuid;
  roundNo: number;
  title: string;
  expectedOutput: string | null;
  deadline: IsoInstant;
  difficulty: Difficulty | null;
  taskStatus: TaskStatus;
  timeliness: TaskTimeliness;
  timelinessLabel: string;
  isLate: boolean;
  /** Lần nộp thật mới nhất (điền sẵn minh chứng); null nếu chưa nộp. */
  submission: SubmissionBrief | null;
  declaredResult: CheckpointTaskResult | null;
  /** true = giá trị do server điền sẵn, chưa do người dùng xác nhận. */
  prefilled: boolean;
  riskLevel: ReportRiskLevel | null;
  note: string | null;
  /** Chỉ thông tin; KHÔNG đổi deadline. */
  expectedCompletionAt: IsoInstant | null;
}

export interface BacklogItem {
  taskId: Uuid;
  roundId: Uuid;
  title: string;
  fromCycle: CycleRef;
  state: 'CARRYOVER_REQUIRED' | 'AWAITING_REVIEW_PREVIOUS_CYCLE';
  /** Vd "Cần TL chốt lịch xử lý tiếp" / "Chờ TL duyệt ở kỳ trước". */
  message: string;
}

export interface ReportCompleteness {
  requiredCount: number;
  respondedCount: number;
  missingTaskIds: Uuid[];
  isComplete: boolean;
}

export interface MyReportResponse {
  serverTime: IsoInstant;
  cycle: CheckpointCycle;
  state: MemberReportState;
  /** Số revision báo cáo hiện tại (null nếu chưa có). */
  revisionNo: number | null;
  status: CheckpointReportStatus | null;
  submittedAt: IsoInstant | null;
  canEdit: boolean;
  lockReason: ReasonRef | null;
  items: ReportItem[];
  /** Tồn đọng chưa được TL chốt deadline mới + task chỉ chờ duyệt ở kỳ trước (không bắt buộc báo cáo). */
  backlog: BacklogItem[];
  completeness: ReportCompleteness;
  /** "Đã gửi đầy đủ" chỉ khi isComplete và đã SUBMITTED; ngược lại null. */
  submittedLabel: string | null;
  version: number | null;
}

export interface ReportItemInput {
  taskId: Uuid;
  declaredResult: CheckpointTaskResult;
  riskLevel: ReportRiskLevel;
  /** ≤1000 ký tự. */
  note?: string | null;
  expectedCompletionAt?: IsoInstant | null;
}

export interface SaveReportRequest {
  items: ReportItemInput[];
  /** version nhận từ MyReportResponse (null nếu chưa có báo cáo). */
  version: number | null;
}

export interface SubmitReportRequest {
  /** Nếu có: lưu rồi gửi trong một thao tác; nếu bỏ qua: gửi bản nháp hiện có. */
  items?: ReportItemInput[] | null;
  version: number | null;
}

// ═════════════════════════════ HỖ TRỢ RIÊNG & RISK (BE-B · support) ═════════════════════════════

export interface CreateSupportRequestRequest {
  obstacleType: ObstacleType;
  /** Mô tả ngắn, 10–2000 ký tự. */
  description: string;
  /** Phần cần hỗ trợ, 5–1000 ký tự. */
  neededSupport: string;
  /** Ảnh hưởng, ≤1000 ký tự. */
  impact?: string | null;
  expectedDate?: IsoDate | null;
  /** Vòng đang làm (tùy chọn, để gắn ngữ cảnh). */
  roundId?: Uuid | null;
}

export interface SupportRequestSummary {
  id: Uuid;
  taskId: Uuid;
  taskTitle: string;
  sender: UserRef;
  obstacleType: ObstacleType;
  status: SupportRequestStatus;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

/** CHỈ người gửi và TL đọc được. */
export interface SupportRequest extends SupportRequestSummary {
  groupId: Uuid;
  roundId: Uuid | null;
  description: string;
  neededSupport: string;
  impact: string | null;
  expectedDate: IsoDate | null;
  decision: SupportRequestDecision | null;
  /** Phản hồi/kết luận của TL, người gửi đọc được. */
  leadResponse: string | null;
  reviewedBy: UserRef | null;
  reviewedAt: IsoInstant | null;
  /** Các yêu cầu công khai đã đăng từ đề nghị này (id thôi, không kèm nội dung riêng). */
  publishedHelpRequestIds: Uuid[];
  version: number;
  allowedActions: SupportRequestAction[];
}

export interface ListSupportRequestsQuery extends PageQuery {
  status?: SupportRequestStatus;
  taskId?: Uuid;
  /** MINE = chỉ của tôi. Member luôn bị ép MINE. Mặc định ALL với TL. */
  scope?: 'ALL' | 'MINE';
}

export interface SupportRequestDecisionRequest {
  action: SupportRequestLeadAction;
  /** Phản hồi cho người gửi, 1–2000 ký tự. */
  response: string;
  version: number;
}

export interface RiskReport {
  id: Uuid;
  groupId: Uuid;
  taskId: Uuid | null;
  taskTitle: string | null;
  subject: UserRef;
  reportedBy: UserRef;
  riskType: RiskType;
  /** Chỉ nội dung được phép chia sẻ cho cả nhóm. */
  publicSummary: string;
  status: RiskReportStatus;
  confirmedBy: UserRef | null;
  confirmedAt: IsoInstant | null;
  resolvedAt: IsoInstant | null;
  affectedTaskIds: Uuid[];
  createdAt: IsoInstant;
  version: number;
}

export interface ListRiskReportsQuery extends PageQuery {
  status?: RiskReportStatus;
  taskId?: Uuid;
}

export interface CreateRiskReportRequest {
  riskType: RiskType;
  taskId?: Uuid | null;
  /** Mặc định người thực hiện của task; bắt buộc nếu không có taskId hoặc riskType = DROPOUT_RISK. */
  subjectUserId?: Uuid | null;
  /** 5–500 ký tự, không chứa nội dung hỗ trợ riêng. */
  publicSummary: string;
  /** Task khác bị ảnh hưởng; người thực hiện nhận RISK_RELEVANT. */
  affectedTaskIds?: Uuid[];
}

export interface ResolveRiskRequest {
  note?: string | null;
  version: number;
}

// ═════════════════════════════ HỖ TRỢ CÔNG KHAI (BE-B · help) ═════════════════════════════

export interface PointImpact {
  potentialBefore: PointAmount;
  potentialAfter: PointAmount;
  delta: PointDelta;
}

export interface HelpRequestCard {
  id: Uuid;
  groupId: Uuid;
  title: string;
  /** Mô tả rút gọn (≤200 ký tự) cho card 2 dòng. */
  descriptionPreview: string;
  expectedOutput: string | null;
  task: TaskRef;
  postedBy: UserRef;
  beneficiary: UserRef;
  /** Deadline của vòng hiện tại của task (server điền). */
  deadline: IsoInstant;
  difficulty: Difficulty;
  estimatedEffortMinutes: number | null;
  /** P_t cố định; null khi task không có điểm. */
  points: PointAmount | null;
  /** Vd "Khó · +10,0000 điểm % khi TL duyệt"; null khi không có điểm. */
  pointLabel: string | null;
  status: HelpRequestStatus;
  /** Vd "Đang tìm người nhận làm thay toàn bộ task". */
  statusLabel: string;
  myRoleInRequest: HelpRoleInRequest;
  myOfferStatus: SupportOfferStatus | null;
  /** Có khi tôi là Helper hoặc Requester/Beneficiary và đã có SupportWork. */
  workId: Uuid | null;
  createdAt: IsoInstant;
}

export interface SupportOffer {
  id: Uuid;
  helpRequestId: Uuid;
  helper: UserRef;
  message: string | null;
  status: SupportOfferStatus;
  /** true = yêu cầu đã đổi phạm vi quan trọng sau khi gửi; Helper cần xác nhận lại. */
  needsReconfirmation: boolean;
  createdAt: IsoInstant;
  decidedAt: IsoInstant | null;
  decidedBy: UserRef | null;
}

export interface HelpRequestDetail extends HelpRequestCard {
  /** Phạm vi/tiêu chí hoàn thành đầy đủ. */
  description: string;
  skillNotes: string | null;
  /** Chỉ TL thấy (liên kết nội bộ tới đề nghị riêng); người khác nhận null. */
  supportRequestId: Uuid | null;
  /** Người duyệt kết quả (TL đăng, hoặc người duyệt độc lập với task của TL). */
  resultApprover: UserRef;
  /** Đề nghị của tôi (nếu có). */
  myOffer: SupportOffer | null;
  /** Danh sách đề nghị: CHỈ Requester thấy; người khác nhận []. */
  offers: SupportOffer[];
  work: SupportWorkSummary | null;
  /** Ảnh hưởng tới điểm của tôi nếu tôi nhận; null nếu không áp dụng (không có điểm / tôi không thể nhận). */
  myImpact: PointImpact | null;
  /** Giải thích điểm được chuyển toàn bộ từ quỹ task (hiển thị nguyên văn). */
  pointExplanation: string | null;
  allowedActions: HelpRequestAction[];
  version: number;
}

export interface ListHelpRequestsQuery extends PageQuery {
  /** Mặc định OPEN. */
  tab?: HelpRequestTab;
  difficulty?: Difficulty;
  deadlineFrom?: IsoInstant;
  deadlineTo?: IsoInstant;
  /** Lọc theo điểm task có thể nhận (units). */
  minPointUnits?: number;
  maxPointUnits?: number;
  maxEffortMinutes?: number;
}

export interface CreateHelpRequestRequest {
  taskId: Uuid;
  /** Phải đúng người thực hiện hiện tại của task (spec yêu cầu khai rõ). */
  beneficiaryId: Uuid;
  /** Liên kết ngược tới đề nghị riêng (nếu có). */
  supportRequestId?: Uuid | null;
  /** 3–120 ký tự. */
  title: string;
  /** Phạm vi/tiêu chí hoàn thành do TL tự viết, 10–2000 ký tự, không lộ lý do riêng tư. */
  description: string;
  /** Mặc định lấy từ kế hoạch có hiệu lực. */
  expectedOutput?: string | null;
  estimatedEffortMinutes?: number | null;
  skillNotes?: string | null;
  /** TL xác nhận đã kiểm tra nội dung không lộ lý do riêng tư; phải là true. */
  privacyChecked: true;
}

export interface UpdateHelpRequestRequest {
  title?: string;
  description?: string;
  expectedOutput?: string | null;
  estimatedEffortMinutes?: number | null;
  skillNotes?: string | null;
  version: number;
}

export interface CancelHelpRequestRequest {
  reason?: string | null;
  version: number;
}

export interface CreateOfferRequest {
  /** ≤500 ký tự. */
  message?: string | null;
  /** Đã xem dialog tóm tắt phạm vi/deadline/P_t/điều kiện "chỉ ghi điểm sau TL duyệt"; phải là true. */
  acknowledgedScope: true;
  /** version của HelpRequestDetail đang xem; lệch → 409 VERSION_CONFLICT. */
  helpRequestVersion: number;
}

export interface ReconfirmOfferRequest {
  helpRequestVersion: number;
}

export interface AcceptOfferRequest {
  offerId: Uuid;
  /** version của HelpRequestDetail. */
  version: number;
}

export interface AcceptOfferResponse {
  helpRequest: HelpRequestDetail;
  work: SupportWork;
  /** Task sau khi bàn giao người thực hiện sang Helper. */
  task: TaskSummary;
  notSelectedOfferIds: Uuid[];
}

export interface SupportWorkSummary {
  id: Uuid;
  helpRequestId: Uuid;
  title: string;
  helper: UserRef;
  /** Người đăng yêu cầu (TL). */
  requester: UserRef;
  status: SupportWorkStatus;
  /** Trạng thái yêu cầu công khai gốc (CANCELLED khi TL đóng/mở lại sau NEEDS_DISCUSSION). */
  helpRequestStatus: HelpRequestStatus;
  /** "Đang thực hiện" | "Đã gửi kết quả" | "Cần bổ sung" | "Cần thảo luận" | "Đã xác nhận". */
  statusLabel: string;
  points: PointAmount | null;
  /** false cho đến khi TL duyệt → "Chưa ghi điểm". */
  pointRecorded: boolean;
  contributionStatus: ContributionStatus | null;
  updatedAt: IsoInstant;
}

export interface SupportSubmission {
  id: Uuid;
  workId: Uuid;
  revisionNo: number;
  resultNote: string;
  evidence: EvidenceItem[];
  /** "Tự khai" — chỉ ghi nhận công sức, không đổi điểm. */
  actualMinutes: number | null;
  submittedAt: IsoInstant;
  /** Bản nộp tương ứng trên task gốc (dùng cho review). */
  taskSubmissionId: Uuid;
  reviewDecision: TaskReviewDecision | null;
  issueDescription: string | null;
}

export interface Contribution {
  id: Uuid;
  workId: Uuid;
  helper: UserRef;
  status: ContributionStatus;
  acceptedSubmissionId: Uuid | null;
  confirmedBy: UserRef | null;
  confirmedAt: IsoInstant | null;
  ledgerEntryId: Uuid | null;
}

export interface SupportTimelineEvent {
  at: IsoInstant;
  kind: SupportTimelineKind;
  actor: UserRef | null;
  note: string | null;
}

export interface SupportWork extends SupportWorkSummary {
  groupId: Uuid;
  task: TaskRef;
  /** Người phụ trách gốc / beneficiary. */
  originalAssignee: UserRef;
  resultApprover: UserRef;
  scopeSnapshot: string;
  expectedOutput: string | null;
  deadlineSnapshot: IsoInstant;
  difficultySnapshot: Difficulty;
  /** Vd "+10,0000 điểm % khi TL duyệt". */
  pointLabel: string | null;
  /** Vòng hiện tại của task gốc mà Helper đang làm; null nếu đang chờ TL chốt deadline xử lý tiếp. */
  roundId: Uuid | null;
  submissions: SupportSubmission[];
  timeline: SupportTimelineEvent[];
  contribution: Contribution | null;
  /** Bản nộp task mới nhất → dùng cho GET /task-submissions/{id}/review-preview và review. */
  latestTaskSubmissionId: Uuid | null;
  /** Giờ chốt của kỳ chứa vòng hiện tại. */
  cutoffAt: IsoInstant | null;
  canSubmit: boolean;
  cannotSubmitReason: ReasonRef | null;
  /** now > cutoffAt và có bản nộp chờ duyệt. */
  canReviewNow: boolean;
  /** Có khi TL chọn REOPEN_REQUEST: yêu cầu công khai mới (OPEN) thay thế yêu cầu này. */
  successorHelpRequestId: Uuid | null;
  allowedActions: SupportWorkAction[];
  version: number;
}

export interface SubmitSupportResultRequest {
  /** 10–1000 ký tự. */
  resultNote: string;
  /** 1–5 minh chứng. */
  evidence: EvidenceItem[];
  /** Tự khai, số nguyên dương (phút); tùy chọn. */
  actualMinutes?: number | null;
}

export interface SubmitSupportResultResponse {
  submission: SupportSubmission;
  work: SupportWork;
}

export interface ReportUnableRequest {
  /** 5–1000 ký tự. */
  reason: string;
  version: number;
}

export interface ResolveDiscussionRequest {
  resolution: SupportWorkResolution;
  /** 5–1000 ký tự. */
  note: string;
  version: number;
}

// ═════════════════════════════ ĐIỂM (BE-B · points) ═════════════════════════════
// Mọi lượng điểm là PointAmount (pointUnits nguyên + percentLabel); biến động có dấu là PointDelta.
// Tham số điểm trong request/query luôn là số nguyên units (hậu tố `Units`).

export interface PointPolicy {
  id: Uuid;
  versionNo: number;
  status: PointPolicyStatus;
  /** Tổng quỹ dự án: luôn 1.000.000 units = 100%. */
  totalPoints: PointAmount;
  weights: Record<Difficulty, number>;
  /** N lúc khóa (gồm TL nếu TL tham gia). */
  memberCountAtBaseline: number;
  approvedBy: UserRef | null;
  approvedAt: IsoInstant | null;
  createdAt: IsoInstant;
}

export interface PointAllocationTask {
  taskId: Uuid;
  title: string;
  difficulty: Difficulty;
  weight: number;
  /** P_t đã khóa (hoặc dự kiến trong preview). */
  points: PointAmount;
}

export interface PointAllocationMember {
  user: UserRef;
  /** B_i — phần ban đầu. */
  initialPoints: PointAmount;
  /** R_i — phần dự phòng giữ lại (chưa gắn task). */
  reservePoints: PointAmount;
  /** Σ P_t của các task đã định giá cho người này. */
  pricedPoints: PointAmount;
  /** W_i — tổng trọng số task được định giá. */
  weightSum: number;
  taskCount: number;
  tasks: PointAllocationTask[];
  /** Vd "W_i = 0: giữ B_i làm quỹ chưa phân bổ". */
  warnings: string[];
}

export interface PointAllocation {
  totalPoints: PointAmount;
  members: PointAllocationMember[];
  /** Quỹ chưa phân bổ (Σ R_i còn lại). */
  unallocatedPoints: PointAmount;
}

export interface PointPolicyIssue {
  code: ErrorCode;
  message: string;
  taskId: Uuid | null;
  userId: Uuid | null;
}

export interface PointPolicyResponse {
  /** null khi chưa từng khóa phương án. */
  policy: PointPolicy | null;
  active: boolean;
  /** "Chưa có phương án điểm đã duyệt" khi active=false. */
  statusLabel: string | null;
  /** Bảng W_i / B_i / P_t cho TL; Member nhận null. */
  allocation: PointAllocation | null;
}

export interface ReserveInput {
  userId: Uuid;
  /** R_i (units, ≥0, ≤ phần còn tự do của người đó). */
  reservePointUnits: number;
}

export interface PointPolicyPreviewRequest {
  /** Danh sách người nằm trong phương án (mặc định mọi thành viên đang hoạt động, gồm TL). */
  participantUserIds?: Uuid[] | null;
  reserves?: ReserveInput[] | null;
}

export interface PointPolicyPreviewResponse {
  /** INITIAL = lần khóa đầu (B_i = 100/N); ADDITIONAL = định giá thêm task mới từ quỹ dự phòng. */
  basis: PointPolicyBasis;
  nextVersionNo: number;
  memberCount: number;
  allocation: PointAllocation;
  /** Bất biến Σ = 1.000.000 units. */
  sumCheck: { expectedUnits: number; actualUnits: number; ok: boolean };
  blockingIssues: PointPolicyIssue[];
  canActivate: boolean;
}

export interface ActivatePointPolicyRequest extends PointPolicyPreviewRequest {
  /** TL xác nhận phương án đã thống nhất với những người bị ảnh hưởng; phải là true. */
  confirmedWithMembers: true;
}

export interface ActivatePointPolicyResponse {
  policy: PointPolicy;
  allocation: PointAllocation;
  fundedTaskCount: number;
}

export interface PointBreakdownCount {
  points: PointAmount;
  taskCount: number;
}

export interface MyPointsResponse {
  policyActive: boolean;
  /** "Chưa có phương án điểm đã duyệt" khi chưa khóa. */
  statusLabel: string | null;
  memberCount: number | null;
  /** B_i; null khi chưa có phương án. */
  initialPoints: PointAmount | null;
  /** Tổng ròng ledger (đã được TL xác nhận). */
  confirmedPoints: PointAmount;
  /** allocated − confirmed (còn nằm trong task thuộc quyền nhận hiện tại). */
  pendingPoints: PointAmount;
  /** = allocated = confirmed + pending (KHÔNG tự chặn ở phần ban đầu). */
  potentialPoints: PointAmount;
  transferredOut: PointBreakdownCount;
  takenOver: PointBreakdownCount;
  /** Quỹ P_t đang giữ cho yêu cầu mở chưa có Helper. */
  pendingTransfer: { heldPoints: PointAmount; openRequestCount: number };
  unallocatedReservePoints: PointAmount;
  /** confirmed / initial × 100, định dạng sẵn (vd "100,0000"); null nếu không có initial. */
  goalCompletionPercentLabel: string | null;
}

export interface PointTaskRow {
  taskId: Uuid;
  title: string;
  difficulty: Difficulty;
  points: PointAmount;
  originalAssignee: UserRef;
  currentRecipient: UserRef;
  source: PointTaskSource;
  cycle: CycleRef | null;
  deadline: IsoInstant | null;
  status: PointTaskStatus;
  /** "Chưa làm" | "Đang làm" | "Chờ duyệt" | "Cần sửa" | "Đã ghi điểm" | "Đã chuyển đi". */
  statusLabel: string;
  transferredTo: UserRef | null;
  heldForRequest: boolean;
  helpRequestId: Uuid | null;
  workId: Uuid | null;
}

export interface ListPointTasksQuery extends PageQuery {
  status?: PointTaskStatus;
  source?: PointTaskSource;
}

export interface PointHistoryItem {
  id: Uuid;
  group: PointHistoryGroup;
  kind: PointHistoryKind;
  occurredAt: IsoInstant;
  task: TaskRef | null;
  /** Chỉ có với group = POINTS_RECORDED. */
  delta: PointDelta | null;
  /** Lũy kế sau khoản này (chỉ với POINTS_RECORDED). */
  runningTotal: PointAmount | null;
  source: PointTaskSource | null;
  approvedBy: UserRef | null;
  /** Vd "Đã ghi nhận +10,0000 điểm % từ task T". */
  label: string;
  /** Lý do (bắt buộc với REVERSAL). */
  reason: string | null;
  ledgerEntryId: Uuid | null;
  workId: Uuid | null;
}

export interface ListPointHistoryQuery extends PageQuery {
  /** Mặc định ALL. */
  group?: PointHistoryGroup | 'ALL';
}

export interface LedgerEntryDetail {
  entry: PointHistoryItem;
  task: TaskRef;
  /** P_t của quỹ task. */
  budgetPoints: PointAmount;
  originalAssignee: UserRef;
  /** Minh chứng chỉ có khi người gọi được phép xem. */
  submission: TaskSubmission | null;
  review: TaskResultReview | null;
  contribution: Contribution | null;
  settlement: {
    id: Uuid;
    approvedBy: UserRef;
    approvedAt: IsoInstant;
    revokedAt: IsoInstant | null;
    revokeReason: string | null;
  };
  workId: Uuid | null;
  entryType: LedgerEntryType;
  reversesEntryId: Uuid | null;
  reversedByEntryId: Uuid | null;
}

export interface SimulationCandidate {
  taskId: Uuid;
  title: string;
  difficulty: Difficulty;
  points: PointAmount;
  owner: UserRef;
  helpRequestId: Uuid | null;
}

export interface SimulationCandidatesResponse {
  /** Task của tôi chưa ghi điểm, chưa có người khác nhận. */
  transferable: SimulationCandidate[];
  /** Task của người khác đang có yêu cầu hỗ trợ mở (chỉ task chưa ghi điểm, chưa có người nhận). */
  takeable: SimulationCandidate[];
}

export interface SimulatePointsRequest {
  transferOutTaskIds?: Uuid[];
  takeOnTaskIds?: Uuid[];
}

export interface SimulationStep {
  kind: SimulationStepKind;
  /** Vd "Chuyển task 10 điểm % → còn 10%". */
  label: string;
  taskId: Uuid | null;
  delta: PointDelta | null;
  potentialAfter: PointAmount;
}

export interface SimulatePointsResponse {
  confirmedPoints: PointAmount;
  startPotentialPoints: PointAmount;
  endPotentialPoints: PointAmount;
  steps: SimulationStep[];
  /** "Đây là mô phỏng, không ghi điểm". */
  notice: string;
}

export interface GroupPointsSummary {
  policyActive: boolean;
  statusLabel: string | null;
  /** 100% = 1.000.000 units. */
  totalPoints: PointAmount;
  /** X — đã được xác nhận. */
  confirmedPoints: PointAmount;
  /** Y — chưa được xác nhận (đã gồm quỹ đang giữ cho yêu cầu mở). */
  pendingPoints: PointAmount;
  /** Z — chưa phân bổ. X + Y + Z = total. */
  unallocatedPoints: PointAmount;
}

export interface ReverseSettlementRequest {
  /** 10–1000 ký tự. */
  reason: string;
  /** Nếu có: tạo settlement mới đúng người trong cùng transaction (phải là người thực hiện hợp lệ của bản đã duyệt). */
  reissueRecipientUserId?: Uuid | null;
}

export interface ReverseSettlementResponse {
  settlementId: Uuid;
  reversalEntryId: Uuid;
  newSettlementId: Uuid | null;
  newLedgerEntryId: Uuid | null;
  task: TaskSummary;
}

// ═════════════════════════════ THÔNG BÁO (BE-A · notification) ═════════════════════════════

export interface NotificationItem {
  id: Uuid;
  type: NotificationType;
  priority: NotificationPriority;
  groupId: Uuid;
  groupName: string;
  /** Tiêu đề ngắn đã dựng sẵn (không chứa nội dung riêng tư). */
  title: string;
  message: string;
  /** Tên task/yêu cầu liên quan để hiển thị dưới tiêu đề. */
  subjectLabel: string | null;
  entityType: NotificationEntityType;
  entityId: Uuid;
  cycleId: Uuid | null;
  target: ScreenTarget;
  isRead: boolean;
  readAt: IsoInstant | null;
  createdAt: IsoInstant;
}

export interface ListNotificationsQuery extends PageQuery {
  groupId?: Uuid;
  /** Mặc định ALL. */
  tab?: NotificationTab;
  unreadOnly?: boolean;
}

export interface UnreadCountResponse {
  total: number;
  actionRequired: number;
  byGroup: { groupId: Uuid; count: number }[];
}

export interface OpenNotificationResponse {
  notification: NotificationItem;
  /** false → hiển thị `unavailableMessage` và điều hướng tới `fallback`. */
  available: boolean;
  target: ScreenTarget;
  unavailableMessage: string | null;
  fallback: ScreenTarget | null;
}

export interface MarkAllReadRequest {
  groupId?: Uuid | null;
  tab?: NotificationTab;
}

export interface MarkAllReadResponse {
  updated: number;
}

/** Màn hình đích mặc định của từng loại thông báo (server dựng `target` theo bảng này). */
export const NOTIFICATION_TARGET_SCREEN: Record<NotificationType, ScreenId> = {
  TASK_ASSIGNED: 'S05',
  PLAN_SUBMITTED: 'S05',
  PLAN_CHANGE_SUBMITTED: 'S05',
  PLAN_APPROVED: 'S05',
  PLAN_CHANGES_REQUESTED: 'S05',
  CHECKPOINT_REMINDER: 'S06',
  CHECKPOINT_REVIEW_REQUIRED: 'S03',
  CHECKPOINT_SCHEDULE_CHANGED: 'S03',
  NEED_HELP: 'S07',
  SUPPORT_DECISION: 'S07',
  HELP_PUBLISHED: 'S09',
  RISK_RELEVANT: 'S05',
  SUPPORT_OFFERED: 'S09',
  SUPPORT_ACCEPTED: 'S10',
  SUPPORT_SUBMITTED: 'S11',
  REVISION_REQUESTED: 'S10',
  CONTRIBUTION_CONFIRMED: 'S12',
  POINTS_AWARDED: 'S12',
  POINT_ASSIGNMENT_CHANGED: 'S12',
  TASK_REVIEWED: 'S05',
  TASK_CARRIED_OVER: 'S05',
  SUPPORT_NOT_SELECTED: 'S09',
  SUPPORT_WORK_ISSUE: 'S10',
  POINTS_ADJUSTED: 'S12',
  POINT_POLICY_ACTIVATED: 'S12',
};

/** Mức ưu tiên mặc định của từng loại thông báo (tab "Cần hành động" = ACTION_REQUIRED, "Thông tin" = còn lại). */
export const NOTIFICATION_PRIORITY: Record<NotificationType, NotificationPriority> = {
  TASK_ASSIGNED: 'ACTION_REQUIRED',
  PLAN_SUBMITTED: 'ACTION_REQUIRED',
  PLAN_CHANGE_SUBMITTED: 'ACTION_REQUIRED',
  PLAN_APPROVED: 'RELEVANT_CHANGE',
  PLAN_CHANGES_REQUESTED: 'ACTION_REQUIRED',
  CHECKPOINT_REMINDER: 'ACTION_REQUIRED',
  CHECKPOINT_REVIEW_REQUIRED: 'ACTION_REQUIRED',
  CHECKPOINT_SCHEDULE_CHANGED: 'RELEVANT_CHANGE',
  NEED_HELP: 'ACTION_REQUIRED',
  SUPPORT_DECISION: 'RELEVANT_CHANGE',
  HELP_PUBLISHED: 'RELEVANT_CHANGE',
  RISK_RELEVANT: 'RELEVANT_CHANGE',
  SUPPORT_OFFERED: 'ACTION_REQUIRED',
  SUPPORT_ACCEPTED: 'RELEVANT_CHANGE',
  SUPPORT_SUBMITTED: 'ACTION_REQUIRED',
  REVISION_REQUESTED: 'ACTION_REQUIRED',
  CONTRIBUTION_CONFIRMED: 'INFORMATIONAL',
  POINTS_AWARDED: 'INFORMATIONAL',
  POINT_ASSIGNMENT_CHANGED: 'RELEVANT_CHANGE',
  TASK_REVIEWED: 'RELEVANT_CHANGE',
  TASK_CARRIED_OVER: 'ACTION_REQUIRED',
  SUPPORT_NOT_SELECTED: 'RELEVANT_CHANGE',
  SUPPORT_WORK_ISSUE: 'ACTION_REQUIRED',
  POINTS_ADJUSTED: 'INFORMATIONAL',
  POINT_POLICY_ACTIVATED: 'RELEVANT_CHANGE',
};

// ═════════════════════════════ DEMO (BE-B · chỉ profile `demo`) ═════════════════════════════

export interface DemoAccount {
  userId: Uuid;
  name: string;
  email: string;
  /** Mật khẩu demo (chỉ tồn tại ở profile demo). */
  password: string;
  role: GroupRole;
  note: string | null;
}

export interface DemoAccountsResponse {
  groupId: Uuid;
  items: DemoAccount[];
}

export interface DemoClockResponse {
  now: IsoInstant;
  /** true = đồng hồ đang cố định/điều khiển được; false = đồng hồ hệ thống. */
  fixed: boolean;
}

export interface SetDemoClockRequest {
  /** Đặt thời gian tuyệt đối. */
  now?: IsoInstant;
  /** Hoặc tiến thêm N phút (≥1). Cần đúng một trong `now` / `advanceMinutes`. */
  advanceMinutes?: number;
  /** Chạy job tới hạn (nhắc, chốt kỳ, tóm tắt TL) ngay sau khi đổi giờ. Mặc định true. */
  runDueJobs?: boolean;
}

// ═════════════════════════════ BẢN ĐỒ ENDPOINT ═════════════════════════════
// Khóa = "METHOD /path" (không có tiền tố /api/v1), khớp đúng tiêu đề trong API_CONTRACT.md.
// `undefined` = endpoint không có params/query/body.

type N = undefined;
type GroupP = { groupId: Uuid };
type CycleP = { groupId: Uuid; cycleId: Uuid };

export interface ApiEndpoints {
  // ── Auth ──
  'GET /auth/config': EndpointDef<N, N, N, AuthConfigResponse>;
  'POST /auth/register': EndpointDef<N, N, RegisterRequest, AuthResponse>;
  'POST /auth/login': EndpointDef<N, N, LoginRequest, AuthResponse>;
  'POST /auth/google': EndpointDef<N, N, GoogleLoginRequest, AuthResponse>;
  'GET /auth/me': EndpointDef<N, N, N, UserProfile>;

  // ── Groups / members / invites ──
  'GET /groups': EndpointDef<N, N, N, ItemsResponse<GroupCard>>;
  'POST /groups': EndpointDef<N, N, CreateGroupRequest, CreateGroupResponse>;
  'GET /groups/{groupId}': EndpointDef<GroupP, N, N, Group>;
  'PATCH /groups/{groupId}': EndpointDef<GroupP, N, UpdateGroupRequest, Group>;
  'GET /groups/{groupId}/members': EndpointDef<GroupP, N, N, ItemsResponse<Member>>;
  'GET /groups/{groupId}/invite': EndpointDef<GroupP, N, N, Invite>;
  'POST /groups/{groupId}/invite': EndpointDef<GroupP, N, N, Invite>;
  'DELETE /groups/{groupId}/invite': EndpointDef<GroupP, N, N, void>;
  'GET /invites/{code}': EndpointDef<{ code: string }, N, N, InvitePreview>;
  'POST /invites/{code}/accept': EndpointDef<{ code: string }, N, N, AcceptInviteResponse>;

  // ── Schedule & cycles ──
  'POST /checkpoint-schedules/preview': EndpointDef<N, N, SchedulePreviewRequest, SchedulePreviewResponse>;
  'GET /groups/{groupId}/checkpoint-schedules': EndpointDef<GroupP, N, N, ItemsResponse<CheckpointSchedule>>;
  'POST /groups/{groupId}/checkpoint-schedules': EndpointDef<GroupP, N, SaveScheduleRequest, SaveScheduleResponse>;
  'GET /groups/{groupId}/checkpoint-cycles': EndpointDef<GroupP, ListCyclesQuery, N, Page<CheckpointCycle>>;
  'GET /groups/{groupId}/checkpoint-cycles/current': EndpointDef<GroupP, N, N, CheckpointCycleDetail>;
  'GET /groups/{groupId}/checkpoint-cycles/{cycleId}': EndpointDef<CycleP, N, N, CheckpointCycleDetail>;
  'GET /groups/{groupId}/checkpoint-cycles/{cycleId}/lead-summary': EndpointDef<CycleP, { view?: LeadSummaryView }, N, LeadCycleSummary>;

  // ── Dashboard ──
  'GET /groups/{groupId}/dashboard': EndpointDef<GroupP, { cycleId?: Uuid }, N, DashboardResponse>;
  'GET /groups/{groupId}/dashboard/members/{userId}': EndpointDef<
    { groupId: Uuid; userId: Uuid },
    { cycleId?: Uuid },
    N,
    MemberProgressDetail
  >;

  // ── Notifications ──
  'GET /notifications': EndpointDef<N, ListNotificationsQuery, N, Page<NotificationItem>>;
  'GET /notifications/unread-count': EndpointDef<N, { groupId?: Uuid }, N, UnreadCountResponse>;
  'PATCH /notifications/{notificationId}/read': EndpointDef<{ notificationId: Uuid }, N, N, NotificationItem>;
  'POST /notifications/{notificationId}/open': EndpointDef<{ notificationId: Uuid }, N, N, OpenNotificationResponse>;
  'POST /notifications/read-all': EndpointDef<N, N, MarkAllReadRequest, MarkAllReadResponse>;

  // ── Tasks / plans / rounds ──
  'GET /groups/{groupId}/tasks': EndpointDef<GroupP, ListTasksQuery, N, Page<TaskSummary>>;
  'POST /groups/{groupId}/tasks': EndpointDef<GroupP, N, CreateTaskRequest, TaskDetail>;
  'GET /tasks/{taskId}': EndpointDef<{ taskId: Uuid }, N, N, TaskDetail>;
  'GET /tasks/{taskId}/history': EndpointDef<{ taskId: Uuid }, PageQuery, N, Page<TaskHistoryEntry>>;
  'PATCH /plans/{planId}': EndpointDef<{ planId: Uuid }, N, UpdatePlanRequest, PlanMutationResponse>;
  'POST /plans/{planId}/submit': EndpointDef<{ planId: Uuid }, N, VersionedRequest, PlanMutationResponse>;
  'POST /plans/{planId}/withdraw': EndpointDef<{ planId: Uuid }, N, VersionedRequest, PlanMutationResponse>;
  'POST /plans/{planId}/review': EndpointDef<{ planId: Uuid }, N, ReviewPlanRequest, ReviewPlanResponse>;
  'POST /tasks/{taskId}/plan-revisions': EndpointDef<{ taskId: Uuid }, N, ProposeChangeRequest, PlanMutationResponse>;
  'POST /tasks/{taskId}/start': EndpointDef<{ taskId: Uuid }, N, VersionedRequest, TaskSummary>;
  'POST /tasks/{taskId}/progress': EndpointDef<{ taskId: Uuid }, N, ProgressUpdateRequest, ProgressNote>;
  'PUT /tasks/{taskId}/independent-reviewer': EndpointDef<{ taskId: Uuid }, N, SetIndependentReviewerRequest, TaskDetail>;

  // ── Submissions & decisions ──
  'POST /task-rounds/{roundId}/submissions': EndpointDef<{ roundId: Uuid }, N, SubmitResultRequest, SubmitResultResponse>;
  'GET /task-rounds/{roundId}/submissions': EndpointDef<{ roundId: Uuid }, N, N, ItemsResponse<TaskSubmission>>;
  'GET /task-submissions/{submissionId}/review-preview': EndpointDef<{ submissionId: Uuid }, N, N, ReviewPreviewResponse>;
  'POST /task-submissions/{submissionId}/review': EndpointDef<{ submissionId: Uuid }, N, ReviewResultRequest, ReviewResultResponse>;
  'POST /task-rounds/{roundId}/carryover': EndpointDef<{ roundId: Uuid }, N, CarryoverRequest, CarryoverResponse>;
  'GET /groups/{groupId}/checkpoint-cycles/{cycleId}/review-queue': EndpointDef<CycleP, N, N, ReviewQueueResponse>;

  // ── Checkpoint reports ──
  'GET /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report': EndpointDef<CycleP, N, N, MyReportResponse>;
  'PUT /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report': EndpointDef<CycleP, N, SaveReportRequest, MyReportResponse>;
  'POST /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report/submit': EndpointDef<CycleP, N, SubmitReportRequest, MyReportResponse>;

  // ── Private support requests & risks ──
  'POST /tasks/{taskId}/support-requests': EndpointDef<{ taskId: Uuid }, N, CreateSupportRequestRequest, SupportRequest>;
  'GET /groups/{groupId}/support-requests': EndpointDef<GroupP, ListSupportRequestsQuery, N, Page<SupportRequestSummary>>;
  'GET /support-requests/{supportRequestId}': EndpointDef<{ supportRequestId: Uuid }, N, N, SupportRequest>;
  'POST /support-requests/{supportRequestId}/decision': EndpointDef<{ supportRequestId: Uuid }, N, SupportRequestDecisionRequest, SupportRequest>;
  'GET /groups/{groupId}/risk-reports': EndpointDef<GroupP, ListRiskReportsQuery, N, Page<RiskReport>>;
  'POST /groups/{groupId}/risk-reports': EndpointDef<GroupP, N, CreateRiskReportRequest, RiskReport>;
  'POST /risk-reports/{riskReportId}/resolve': EndpointDef<{ riskReportId: Uuid }, N, ResolveRiskRequest, RiskReport>;

  // ── Public help requests / offers / works ──
  'GET /groups/{groupId}/help-requests': EndpointDef<GroupP, ListHelpRequestsQuery, N, Page<HelpRequestCard>>;
  'POST /groups/{groupId}/help-requests': EndpointDef<GroupP, N, CreateHelpRequestRequest, HelpRequestDetail>;
  'GET /help-requests/{helpRequestId}': EndpointDef<{ helpRequestId: Uuid }, N, N, HelpRequestDetail>;
  'PATCH /help-requests/{helpRequestId}': EndpointDef<{ helpRequestId: Uuid }, N, UpdateHelpRequestRequest, HelpRequestDetail>;
  'POST /help-requests/{helpRequestId}/cancel': EndpointDef<{ helpRequestId: Uuid }, N, CancelHelpRequestRequest, HelpRequestDetail>;
  'POST /help-requests/{helpRequestId}/offers': EndpointDef<{ helpRequestId: Uuid }, N, CreateOfferRequest, SupportOffer>;
  'POST /support-offers/{offerId}/withdraw': EndpointDef<{ offerId: Uuid }, N, N, SupportOffer>;
  'POST /support-offers/{offerId}/reconfirm': EndpointDef<{ offerId: Uuid }, N, ReconfirmOfferRequest, SupportOffer>;
  'POST /help-requests/{helpRequestId}/accept-offer': EndpointDef<{ helpRequestId: Uuid }, N, AcceptOfferRequest, AcceptOfferResponse>;
  'GET /support-works/{workId}': EndpointDef<{ workId: Uuid }, N, N, SupportWork>;
  'POST /support-works/{workId}/submissions': EndpointDef<{ workId: Uuid }, N, SubmitSupportResultRequest, SubmitSupportResultResponse>;
  'POST /support-works/{workId}/unable': EndpointDef<{ workId: Uuid }, N, ReportUnableRequest, SupportWork>;
  'POST /support-works/{workId}/resolve-discussion': EndpointDef<{ workId: Uuid }, N, ResolveDiscussionRequest, SupportWork>;

  // ── Points ──
  'GET /groups/{groupId}/point-policy': EndpointDef<GroupP, N, N, PointPolicyResponse>;
  'POST /groups/{groupId}/point-policy/preview': EndpointDef<GroupP, N, PointPolicyPreviewRequest, PointPolicyPreviewResponse>;
  'POST /groups/{groupId}/point-policy/activate': EndpointDef<GroupP, N, ActivatePointPolicyRequest, ActivatePointPolicyResponse>;
  'GET /groups/{groupId}/points/me': EndpointDef<GroupP, N, N, MyPointsResponse>;
  'GET /groups/{groupId}/points/me/tasks': EndpointDef<GroupP, ListPointTasksQuery, N, Page<PointTaskRow>>;
  'GET /groups/{groupId}/points/me/ledger': EndpointDef<GroupP, ListPointHistoryQuery, N, Page<PointHistoryItem>>;
  'GET /groups/{groupId}/points/ledger/{entryId}': EndpointDef<{ groupId: Uuid; entryId: Uuid }, N, N, LedgerEntryDetail>;
  'GET /groups/{groupId}/points/me/simulation-candidates': EndpointDef<GroupP, N, N, SimulationCandidatesResponse>;
  'POST /groups/{groupId}/points/me/simulate': EndpointDef<GroupP, N, SimulatePointsRequest, SimulatePointsResponse>;
  'GET /groups/{groupId}/points/summary': EndpointDef<GroupP, N, N, GroupPointsSummary>;
  'POST /point-settlements/{settlementId}/reverse': EndpointDef<{ settlementId: Uuid }, N, ReverseSettlementRequest, ReverseSettlementResponse>;

  // ── Demo (chỉ profile `demo`) ──
  'GET /demo/accounts': EndpointDef<N, N, N, DemoAccountsResponse>;
  'GET /demo/clock': EndpointDef<N, N, N, DemoClockResponse>;
  'POST /demo/clock': EndpointDef<N, N, SetDemoClockRequest, DemoClockResponse>;
}

export type EndpointKey = keyof ApiEndpoints;

/** Endpoint BẮT BUỘC header `Idempotency-Key` (thiếu → 400 IDEMPOTENCY_KEY_REQUIRED). FE client tự thêm khóa cho các endpoint này. */
export const IDEMPOTENCY_REQUIRED: readonly EndpointKey[] = [
  'POST /plans/{planId}/review',
  'POST /task-rounds/{roundId}/submissions',
  'POST /task-submissions/{submissionId}/review',
  'POST /task-rounds/{roundId}/carryover',
  'POST /groups/{groupId}/checkpoint-cycles/{cycleId}/my-report/submit',
  'POST /groups/{groupId}/help-requests',
  'POST /help-requests/{helpRequestId}/accept-offer',
  'POST /support-works/{workId}/submissions',
  'POST /support-works/{workId}/resolve-discussion',
  'POST /groups/{groupId}/point-policy/activate',
  'POST /point-settlements/{settlementId}/reverse',
] as const;

/** Endpoint nên gửi `Idempotency-Key` (server tôn trọng nếu có). */
export const IDEMPOTENCY_RECOMMENDED: readonly EndpointKey[] = [
  'POST /groups',
  'POST /groups/{groupId}/checkpoint-schedules',
  'POST /plans/{planId}/submit',
  'POST /tasks/{taskId}/plan-revisions',
  'POST /tasks/{taskId}/support-requests',
  'POST /support-requests/{supportRequestId}/decision',
  'POST /groups/{groupId}/tasks',
  'POST /groups/{groupId}/risk-reports',
  'POST /help-requests/{helpRequestId}/offers',
  'POST /support-works/{workId}/unable',
] as const;
