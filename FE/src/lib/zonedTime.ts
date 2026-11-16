/**
 * Chuyển giờ "treo tường" theo múi giờ nhóm <-> thời điểm UTC ISO-8601 của API.
 * Chỉ là đổi biểu diễn giờ người dùng chọn (API chỉ nhận UTC, hợp đồng 1.4); mọi nghiệp vụ giờ/kỳ vẫn do server tính.
 */
import { DEFAULT_TIME_ZONE } from './format'

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/
const TIME_RE = /^(\d{2}):(\d{2})(?::(\d{2}))?$/

const cache = new Map<string, Intl.DateTimeFormat>()

function formatter(timeZone: string): Intl.DateTimeFormat {
  let f = cache.get(timeZone)
  if (!f) {
    try {
      f = new Intl.DateTimeFormat('en-GB', {
        timeZone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
      })
    } catch {
      return formatter(DEFAULT_TIME_ZONE)
    }
    cache.set(timeZone, f)
  }
  return f
}

function partsAt(ms: number, timeZone: string) {
  const p: Record<string, number> = {}
  for (const part of formatter(timeZone).formatToParts(new Date(ms))) {
    if (part.type !== 'literal') p[part.type] = Number(part.value)
  }
  return p as { year: number; month: number; day: number; hour: number; minute: number; second: number }
}

/** Độ lệch (ms) của múi giờ so với UTC tại thời điểm `ms`. */
function offsetAt(ms: number, timeZone: string): number {
  const p = partsAt(ms, timeZone)
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second)
  return asUtc - Math.floor(ms / 1000) * 1000
}

/** `("2026-11-20", "20:00", "Asia/Ho_Chi_Minh")` → `"2026-11-20T13:00:00.000Z"`; sai định dạng → `null`. */
export function zonedTimeToIso(date: string, time: string, timeZone: string = DEFAULT_TIME_ZONE): string | null {
  const d = DATE_RE.exec(date)
  const t = TIME_RE.exec(time)
  if (!d || !t) return null
  const [year, month, day] = [Number(d[1]), Number(d[2]), Number(d[3])]
  const [hour, minute, second] = [Number(t[1]), Number(t[2]), Number(t[3] ?? 0)]
  const guess = Date.UTC(year, month - 1, day, hour, minute, second)
  if (Number.isNaN(guess)) return null
  let utc = guess - offsetAt(guess, timeZone)
  utc = guess - offsetAt(utc, timeZone) // hiệu chỉnh lần hai quanh thời điểm đổi giờ (DST)
  const result = new Date(utc)
  // chặn ngày không tồn tại (vd. 31/02) bị Date tự cuốn sang tháng sau
  const back = partsAt(utc, timeZone)
  if (back.year !== year || back.month !== month || back.day !== day) return null
  return result.toISOString()
}

/** Ngày lịch `YYYY-MM-DD` (IsoDate của API: deadline/ngày bắt đầu nhóm) → `dd/MM/yyyy`. Không đổi múi giờ. */
export function formatCalendarDate(value: string | null | undefined): string {
  const m = value ? DATE_RE.exec(value.slice(0, 10)) : null
  return m ? `${m[3]}/${m[2]}/${m[1]}` : '—'
}

/** `"2026-11-20T13:00:00Z"` → `{ date: "2026-11-20", time: "20:00" }` theo múi giờ nhóm (để điền sẵn form). */
export function isoToZonedInput(iso: string | null | undefined, timeZone: string = DEFAULT_TIME_ZONE): { date: string; time: string } {
  if (!iso) return { date: '', time: '' }
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return { date: '', time: '' }
  const p = partsAt(ms, timeZone)
  const two = (n: number) => String(n).padStart(2, '0')
  return { date: `${p.year}-${two(p.month)}-${two(p.day)}`, time: `${two(p.hour)}:${two(p.minute)}` }
}
