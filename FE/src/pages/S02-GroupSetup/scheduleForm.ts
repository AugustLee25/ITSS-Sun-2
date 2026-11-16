import type { CheckpointSchedule, SchedulePreviewRequest, ScheduleInput } from '@/api/types'

/** Giá trị form lịch checkpoint (mọi trường là chuỗi vì lấy từ input; chuyển sang số khi gửi). */
export interface ScheduleValues {
  weekday: string
  cutoffTime: string
  timezone: string
  /** '' = mở cả kỳ; còn lại là số phút (chuỗi). */
  window: string
  agreed: boolean
}

export const DEFAULT_SCHEDULE: ScheduleValues = {
  weekday: '5',
  cutoffTime: '22:00',
  timezone: 'Asia/Ho_Chi_Minh',
  window: '',
  agreed: false,
}

export const WEEKDAYS = ['1', '2', '3', '4', '5', '6', '7'] as const
export const WINDOW_OPTIONS = ['720', '1440', '2880', '4320'] as const
export const COMMON_TIMEZONES = [
  'Asia/Ho_Chi_Minh',
  'Asia/Bangkok',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Asia/Seoul',
  'Australia/Sydney',
  'Europe/London',
  'America/New_York',
  'UTC',
] as const

export const scheduleValuesFrom = (schedule: CheckpointSchedule): ScheduleValues => ({
  weekday: String(schedule.weekday),
  cutoffTime: schedule.cutoffTime,
  timezone: schedule.timezone,
  window: schedule.reportingWindowMinutes ? String(schedule.reportingWindowMinutes) : '',
  agreed: false,
})

const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/

export function toScheduleInput(values: ScheduleValues): ScheduleInput {
  return {
    weekday: Number(values.weekday),
    cutoffTime: values.cutoffTime,
    timezone: values.timezone,
    reportingWindowMinutes: values.window ? Number(values.window) : null,
  }
}

/** Dữ liệu chưa đủ/không hợp lệ → null (không gọi API xem trước). */
export function toPreviewRequest(
  values: Pick<ScheduleValues, 'weekday' | 'cutoffTime' | 'timezone' | 'window'>,
  context: { groupId?: string; startDate?: string },
): SchedulePreviewRequest | null {
  const weekday = Number(values.weekday)
  if (!Number.isInteger(weekday) || weekday < 1 || weekday > 7) return null
  if (!TIME_RE.test(values.cutoffTime) || !values.timezone) return null
  return {
    ...toScheduleInput({ ...DEFAULT_SCHEDULE, ...values }),
    ...(context.groupId ? { groupId: context.groupId } : { startDate: context.startDate || null }),
  }
}

/** Lấy mã mời từ ô nhập: mã trần, `/join/CODE`, URL đầy đủ hoặc `?invite=CODE`. Không hợp lệ → ''. */
export function extractInviteCode(input: string): string {
  const text = input.trim()
  if (!text) return ''
  const fromPath = /\/join\/([A-Za-z0-9_-]+)/.exec(text)
  if (fromPath?.[1]) return fromPath[1]
  const fromQuery = /[?&]invite=([A-Za-z0-9_-]+)/.exec(text)
  if (fromQuery?.[1]) return fromQuery[1]
  return /^[A-Za-z0-9_-]{4,64}$/.test(text) ? text : ''
}

/** Ngày hôm nay dạng `YYYY-MM-DD` theo giờ máy người dùng (chỉ để gợi ý mặc định; server mới là nơi kiểm tra). */
export function todayInputValue(): string {
  const now = new Date()
  const two = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${two(now.getMonth() + 1)}-${two(now.getDate())}`
}
