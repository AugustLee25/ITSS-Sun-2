import type {
  CheckpointStatus,
  ContributionStatus,
  CyclePhase,
  Difficulty,
  GroupHealth,
  HelpRequestStatus,
  MemberReportState,
  PlanStatus,
  PointTaskStatus,
  ReportRiskLevel,
  RiskStatus,
  RoundState,
  SupportOfferStatus,
  SupportRequestStatus,
  SupportWorkStatus,
  TaskStatus,
  TaskTimeliness,
} from '@/api/types'
import type { MessageKey, TFunction } from '@/i18n'
import type { StatusTone } from './tones'

/**
 * Tông màu cho từng giá trị enum của hợp đồng API; `satisfies Record<Enum, StatusTone>` bắt buộc phủ đủ mọi giá trị.
 * Nhãn chữ: `status.<domain>.<GIÁ_TRỊ>` trong `src/i18n/vi.ts`.
 */
export const STATUS_TONES = {
  plan: {
    DRAFT: 'neutral',
    PENDING_APPROVAL: 'pending',
    CHANGES_REQUESTED: 'warning',
    APPROVED: 'success',
  } satisfies Record<PlanStatus, StatusTone>,
  task: {
    TODO: 'neutral',
    IN_PROGRESS: 'info',
    SUBMITTED: 'pending',
    FIX_REQUIRED: 'warning',
    UPDATE_REQUIRED: 'warning',
    DONE: 'success',
  } satisfies Record<TaskStatus, StatusTone>,
  round: {
    DRAFT: 'muted',
    ACTIVE: 'info',
    AWAITING_REVIEW: 'pending',
    ACCEPTED: 'success',
    CARRYOVER_REQUIRED: 'warning',
    CARRIED_OVER: 'neutral',
  } satisfies Record<RoundState, StatusTone>,
  timeliness: {
    NOT_DUE: 'neutral',
    ON_TIME_PENDING_REVIEW: 'pending',
    ON_TIME_ACCEPTED: 'success',
    LATE_PENDING_REVIEW: 'warning',
    LATE_ACCEPTED: 'warning',
    OVERDUE_NO_RESULT: 'warning',
    NEEDS_REWORK: 'warning',
  } satisfies Record<TaskTimeliness, StatusTone>,
  risk: {
    NORMAL: 'success',
    UNKNOWN: 'muted',
    AT_RISK: 'warning',
    BLOCKED: 'danger',
  } satisfies Record<RiskStatus, StatusTone>,
  reportRisk: {
    ON_TRACK: 'success',
    AT_RISK: 'warning',
    NEEDS_LEAD_HELP: 'danger',
  } satisfies Record<ReportRiskLevel, StatusTone>,
  cyclePhase: {
    TENTATIVE: 'neutral',
    AWAITING_LEAD_REVIEW: 'pending',
    FINALIZED: 'success',
  } satisfies Record<CyclePhase, StatusTone>,
  checkpoint: {
    SCHEDULED: 'neutral',
    OPEN: 'info',
    CLOSED: 'pending',
    FINALIZED: 'success',
    CANCELLED: 'muted',
  } satisfies Record<CheckpointStatus, StatusTone>,
  report: {
    NOT_RESPONDED: 'muted',
    DRAFT: 'neutral',
    SUBMITTED: 'success',
  } satisfies Record<MemberReportState, StatusTone>,
  groupHealth: {
    ON_TRACK: 'success',
    NEEDS_ATTENTION: 'warning',
    INSUFFICIENT_INFO: 'muted',
  } satisfies Record<GroupHealth, StatusTone>,
  support: {
    OPEN: 'pending',
    IN_REVIEW: 'pending',
    RESOLVED: 'success',
  } satisfies Record<SupportRequestStatus, StatusTone>,
  help: {
    OPEN: 'info',
    MATCHED: 'info',
    IN_PROGRESS: 'info',
    SUBMITTED: 'pending',
    COMPLETED: 'success',
    CANCELLED: 'muted',
  } satisfies Record<HelpRequestStatus, StatusTone>,
  offer: {
    PENDING: 'pending',
    ACCEPTED: 'success',
    NOT_SELECTED: 'muted',
    WITHDRAWN: 'muted',
  } satisfies Record<SupportOfferStatus, StatusTone>,
  work: {
    IN_PROGRESS: 'info',
    SUBMITTED: 'pending',
    REVISION_REQUESTED: 'warning',
    NEEDS_DISCUSSION: 'warning',
    APPROVED: 'success',
  } satisfies Record<SupportWorkStatus, StatusTone>,
  contribution: {
    PENDING_CONFIRMATION: 'pending',
    CONFIRMED: 'success',
  } satisfies Record<ContributionStatus, StatusTone>,
  pointTask: {
    NOT_STARTED: 'neutral',
    IN_PROGRESS: 'info',
    AWAITING_REVIEW: 'pending',
    NEEDS_REVISION: 'warning',
    AWARDED: 'success',
    TRANSFERRED_OUT: 'neutral',
  } satisfies Record<PointTaskStatus, StatusTone>,
  difficulty: {
    EASY: 'neutral',
    MEDIUM: 'neutral',
    HARD: 'neutral',
  } satisfies Record<Difficulty, StatusTone>,
}

export type StatusDomain = keyof typeof STATUS_TONES

/** Giá trị enum hợp lệ của một domain, vd. `StatusValue<'plan'>` = `PlanStatus`. */
export type StatusValue<D extends StatusDomain> = keyof (typeof STATUS_TONES)[D] & string

export interface ChipSpec {
  tone: StatusTone
  label: string
}

/**
 * `statusChip(t, 'plan', plan.status)` → `{ tone, label }` truyền thẳng vào `<StatusChip {...} />`.
 * Trong component dùng hook `useStatusChip()` để không phải tự lấy `t`.
 */
export function statusChip<D extends StatusDomain>(t: TFunction, domain: D, value: StatusValue<D>): ChipSpec {
  const tones = STATUS_TONES[domain] as Record<string, StatusTone>
  return { tone: tones[value] ?? 'neutral', label: t(`status.${domain}.${value}` as MessageKey) }
}
