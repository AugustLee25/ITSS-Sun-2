import { createContext, useContext, useMemo } from 'react'
import {
  DEFAULT_TIME_ZONE,
  formatDate,
  formatDateTime,
  formatPointsLabel,
  formatRelativeTime,
  formatTime,
  type DateInput,
} from './format'

/** Múi giờ IANA của nhóm đang xem; GroupLayout cung cấp. Ngoài nhóm dùng mặc định. */
export const TimeZoneContext = createContext<string>(DEFAULT_TIME_ZONE)

export function useTimeZone(): string {
  return useContext(TimeZoneContext)
}

/** Bộ định dạng đã gắn múi giờ nhóm: `const f = useFormatters(); f.dateTime(iso)`. */
export function useFormatters() {
  const timeZone = useTimeZone()
  return useMemo(
    () => ({
      timeZone,
      dateTime: (value: DateInput) => formatDateTime(value, timeZone),
      date: (value: DateInput) => formatDate(value, timeZone),
      time: (value: DateInput) => formatTime(value, timeZone),
      relative: (value: DateInput, now?: DateInput) => formatRelativeTime(value, { now, timeZone }),
      pointsLabel: formatPointsLabel,
    }),
    [timeZone],
  )
}
