import { z } from 'zod'
import type { UseFormSetError } from 'react-hook-form'
import { isApiError } from '@/api/errors'
import { Difficulty, ErrorCode, type CheckpointCycle, type CreateTaskRequest, type PlanRevision } from '@/api/types'
import type { TFunction } from '@/i18n'
import { isoToZonedInput, zonedTimeToIso } from '@/lib/zonedTime'

/** Giá trị form kế hoạch/giao task. Ngày + giờ nhập theo múi giờ NHÓM, đổi sang UTC khi gửi (hợp đồng 1.4). */
export interface PlanFormValues {
  title: string
  assigneeId: string
  cycleId: string
  description: string
  expectedOutput: string
  startDate: string
  startTime: string
  deadlineDate: string
  deadlineTime: string
  difficulty: string
}

export const EMPTY_PLAN: PlanFormValues = {
  title: '',
  assigneeId: '',
  cycleId: '',
  description: '',
  expectedOutput: '',
  startDate: '',
  startTime: '08:00',
  deadlineDate: '',
  deadlineTime: '20:00',
  difficulty: '',
}

export const DIFFICULTIES = [Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD] as const

export function buildPlanSchema(t: TFunction, options: { requireAssignee: boolean }) {
  return z.object({
    title: z.string().trim().min(3, t('tasks.create.errTitle')).max(120, t('tasks.create.errTitle')),
    assigneeId: options.requireAssignee ? z.string().min(1, t('tasks.create.errAssignee')) : z.string(),
    cycleId: z.string().min(1, t('tasks.create.errCycle')),
    description: z.string().trim().min(1, t('tasks.create.errScope')).max(4000, t('tasks.create.errScope')),
    expectedOutput: z.string().trim().min(1, t('tasks.create.errOutput')).max(2000, t('tasks.create.errOutput')),
    startDate: z.string().min(1, t('tasks.create.errStart')),
    startTime: z.string().min(1, t('tasks.create.errStart')),
    deadlineDate: z.string().min(1, t('tasks.create.errDeadline')),
    deadlineTime: z.string().min(1, t('tasks.create.errDeadline')),
    difficulty: z.string().refine((value) => (DIFFICULTIES as readonly string[]).includes(value), t('tasks.create.errDifficulty')),
  })
}

export function planValuesFromRevision(plan: PlanRevision, timeZone: string): PlanFormValues {
  const start = isoToZonedInput(plan.startAt, timeZone)
  const deadline = isoToZonedInput(plan.deadline, timeZone)
  return {
    title: plan.title,
    assigneeId: plan.assignee.userId,
    cycleId: plan.proposedCycle.id,
    description: plan.description ?? '',
    expectedOutput: plan.expectedOutput ?? '',
    startDate: start.date,
    startTime: start.time || '08:00',
    deadlineDate: deadline.date,
    deadlineTime: deadline.time || '20:00',
    difficulty: plan.difficulty ?? '',
  }
}

export type PlanTimes = { startAt: string; deadline: string }

/** Đổi ngày+giờ nhập (múi giờ nhóm) sang UTC; trả lỗi gắn trường nếu sai/ngược thứ tự. */
export function resolvePlanTimes(
  values: Pick<PlanFormValues, 'startDate' | 'startTime' | 'deadlineDate' | 'deadlineTime'>,
  timeZone: string,
  t: TFunction,
): { ok: true; times: PlanTimes } | { ok: false; field: 'startDate' | 'deadlineDate'; message: string } {
  const startAt = zonedTimeToIso(values.startDate, values.startTime, timeZone)
  if (!startAt) return { ok: false, field: 'startDate', message: t('tasks.create.errStart') }
  const deadline = zonedTimeToIso(values.deadlineDate, values.deadlineTime, timeZone)
  if (!deadline) return { ok: false, field: 'deadlineDate', message: t('tasks.create.errDeadline') }
  if (startAt > deadline) return { ok: false, field: 'startDate', message: t('tasks.create.errStartAfterDeadline') }
  return { ok: true, times: { startAt, deadline } }
}

export function toCreateRequest(values: PlanFormValues, times: PlanTimes): CreateTaskRequest {
  return {
    title: values.title.trim(),
    assigneeId: values.assigneeId,
    cycleId: values.cycleId,
    description: values.description.trim(),
    expectedOutput: values.expectedOutput.trim(),
    startAt: times.startAt,
    deadline: times.deadline,
    difficulty: values.difficulty as Difficulty,
  }
}

/** Kỳ còn nhận task/kế hoạch (chưa đóng); luôn giữ kỳ đang chọn dù đã đóng để hiển thị đúng. */
export function selectableCycles(cycles: CheckpointCycle[], keepId?: string): CheckpointCycle[] {
  return cycles
    .filter((cycle) => cycle.status === 'SCHEDULED' || cycle.status === 'OPEN' || cycle.id === keepId)
    .sort((a, b) => a.sequenceNo - b.sequenceNo)
}

const FIELD_OF_API: Record<string, keyof PlanFormValues> = {
  title: 'title',
  assigneeId: 'assigneeId',
  cycleId: 'cycleId',
  description: 'description',
  expectedOutput: 'expectedOutput',
  startAt: 'startDate',
  deadline: 'deadlineDate',
  difficulty: 'difficulty',
}

const FIELD_OF_CODE: Record<string, keyof PlanFormValues> = {
  [ErrorCode.DEADLINE_OUTSIDE_CYCLE]: 'deadlineDate',
  [ErrorCode.DEADLINE_AFTER_GROUP_DEADLINE]: 'deadlineDate',
  [ErrorCode.START_AFTER_DEADLINE]: 'startDate',
  [ErrorCode.ASSIGNEE_NOT_IN_GROUP]: 'assigneeId',
  [ErrorCode.CYCLE_CLOSED]: 'cycleId',
}

/** Gắn lỗi server vào đúng trường của form (kể cả khi chỉ có mã nghiệp vụ). Trả true nếu đã gắn được ít nhất một lỗi. */
export function applyPlanServerErrors<T extends PlanFormValues>(error: unknown, setError: UseFormSetError<T>): boolean {
  if (!isApiError(error)) return false
  const set = setError as unknown as (name: string, error: { type: string; message: string }) => void
  let applied = false
  for (const fieldError of error.fieldErrors) {
    const name = FIELD_OF_API[fieldError.field]
    if (name) {
      set(name, { type: 'server', message: fieldError.message })
      applied = true
    }
  }
  const byCode = FIELD_OF_CODE[error.code]
  if (byCode && !applied) {
    set(byCode, { type: 'server', message: error.message })
    applied = true
  }
  return applied
}

export type PlanChangePayload = Partial<Omit<CreateTaskRequest, 'assigneeId'>> & { assigneeId?: string }

/**
 * So giá trị form với bản gốc → chỉ các trường ĐÃ ĐỔI (API chỉ nhận trường đổi: `PATCH /plans`, `POST …/plan-revisions`).
 * Ngày+giờ chỉ đổi sang UTC khi một trong hai đổi. Kết quả rỗng = không có thay đổi.
 */
export function buildChangePayload(
  values: PlanFormValues,
  initial: PlanFormValues,
  timeZone: string,
  t: TFunction,
): { ok: true; payload: PlanChangePayload } | { ok: false; field: keyof PlanFormValues; message: string } {
  const payload: PlanChangePayload = {}
  if (values.title.trim() !== initial.title.trim()) payload.title = values.title.trim()
  if (values.description.trim() !== initial.description.trim()) payload.description = values.description.trim()
  if (values.expectedOutput.trim() !== initial.expectedOutput.trim()) payload.expectedOutput = values.expectedOutput.trim()
  if (values.cycleId !== initial.cycleId) payload.cycleId = values.cycleId
  if (values.assigneeId && values.assigneeId !== initial.assigneeId) payload.assigneeId = values.assigneeId
  if (values.difficulty !== initial.difficulty) payload.difficulty = values.difficulty as Difficulty
  const startChanged = values.startDate !== initial.startDate || values.startTime !== initial.startTime
  const deadlineChanged = values.deadlineDate !== initial.deadlineDate || values.deadlineTime !== initial.deadlineTime
  if (startChanged || deadlineChanged) {
    const resolved = resolvePlanTimes(values, timeZone, t)
    if (!resolved.ok) return resolved
    if (startChanged) payload.startAt = resolved.times.startAt
    if (deadlineChanged) payload.deadline = resolved.times.deadline
  }
  return { ok: true, payload }
}
