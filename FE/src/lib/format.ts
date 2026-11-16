/**
 * Định dạng hiển thị dùng chung (spec 2.4 #9): thời gian `dd/MM/yyyy, HH:mm` theo múi giờ nhóm,
 * điểm dạng `+4,0000 điểm %`. Server luôn trả UTC ISO-8601; FE chỉ định dạng, không tính nghiệp vụ.
 */
export const DEFAULT_TIME_ZONE = 'Asia/Ho_Chi_Minh'
export const NOT_AVAILABLE = '—'

export type DateInput = string | number | Date | null | undefined

export function toDate(value: DateInput): Date | null {
  if (value === null || value === undefined || value === '') return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

const formatterCache = new Map<string, Intl.DateTimeFormat>()

function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = formatterCache.get(timeZone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-GB', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
    formatterCache.set(timeZone, formatter)
  }
  return formatter
}

function getParts(date: Date, timeZone: string) {
  let formatter: Intl.DateTimeFormat
  try {
    formatter = partsFormatter(timeZone)
  } catch {
    formatter = partsFormatter(DEFAULT_TIME_ZONE) // múi giờ IANA không hợp lệ
  }
  const parts: Record<string, string> = {}
  for (const part of formatter.formatToParts(date)) parts[part.type] = part.value
  return { dd: parts.day, MM: parts.month, yyyy: parts.year, HH: parts.hour, mm: parts.minute }
}

/** `15/11/2026, 17:30` theo múi giờ IANA của nhóm. */
export function formatDateTime(value: DateInput, timeZone: string = DEFAULT_TIME_ZONE): string {
  const date = toDate(value)
  if (!date) return NOT_AVAILABLE
  const p = getParts(date, timeZone)
  return `${p.dd}/${p.MM}/${p.yyyy}, ${p.HH}:${p.mm}`
}

/** `15/11/2026` */
export function formatDate(value: DateInput, timeZone: string = DEFAULT_TIME_ZONE): string {
  const date = toDate(value)
  if (!date) return NOT_AVAILABLE
  const p = getParts(date, timeZone)
  return `${p.dd}/${p.MM}/${p.yyyy}`
}

/** `17:30` */
export function formatTime(value: DateInput, timeZone: string = DEFAULT_TIME_ZONE): string {
  const date = toDate(value)
  if (!date) return NOT_AVAILABLE
  const p = getParts(date, timeZone)
  return `${p.HH}:${p.mm}`
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** Thời gian tương đối ("5 phút trước"); quá 7 ngày — hoặc ở tương lai (đồng hồ demo của server chạy trước máy khách) — thì hiện ngày tuyệt đối. */
export function formatRelativeTime(
  value: DateInput,
  options: { now?: DateInput; locale?: string; timeZone?: string } = {},
): string {
  const date = toDate(value)
  if (!date) return NOT_AVAILABLE
  const now = toDate(options.now) ?? new Date()
  const diff = date.getTime() - now.getTime()
  const abs = Math.abs(diff)
  if (diff > MINUTE) return formatDate(date, options.timeZone)
  const rtf = new Intl.RelativeTimeFormat(options.locale ?? 'vi', { numeric: 'auto' })
  if (abs < MINUTE) return rtf.format(0, 'second')
  if (abs < HOUR) return rtf.format(Math.round(diff / MINUTE), 'minute')
  if (abs < DAY) return rtf.format(Math.round(diff / HOUR), 'hour')
  if (abs < 7 * DAY) return rtf.format(Math.round(diff / DAY), 'day')
  return formatDate(date, options.timeZone)
}

export const POINTS_UNIT = 'điểm %'

/** Dạng tối thiểu của `PointAmount` / `PointDelta` trong hợp đồng API (server đã định dạng sẵn `percentLabel`). */
export interface PointLike {
  pointUnits: number
  percentLabel: string
}

/**
 * Nhãn điểm hiển thị: `"+4,0000 điểm %"` (spec 7.2). FE KHÔNG tự chia/làm tròn điểm — chỉ ghép `percentLabel` do server trả.
 * - `PointDelta.percentLabel` đã mang dấu ("+4,0000" | "-4,0000" | "0,0000").
 * - `PointAmount.percentLabel` không dấu ("10,0000"): truyền `signed: true` để thêm "+" khi lượng điểm dương (vd. "+10,0000 điểm %" ở HELP_PUBLISHED).
 */
export function formatPointsLabel(
  amount: PointLike | null | undefined,
  options: { signed?: boolean; unit?: string | false } = {},
): string {
  if (!amount?.percentLabel) return NOT_AVAILABLE
  let label = amount.percentLabel
  if (options.signed && amount.pointUnits > 0 && !/^[+-]/.test(label)) label = `+${label}`
  return options.unit === false ? label : `${label} ${options.unit ?? POINTS_UNIT}`
}
