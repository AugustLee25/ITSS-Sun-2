import { describe, expect, it } from 'vitest'
import { isoToZonedInput, zonedTimeToIso } from './zonedTime'

describe('zonedTime', () => {
  it('đổi giờ Việt Nam sang UTC (ví dụ hợp đồng: 20:00 VN = 13:00Z)', () => {
    expect(zonedTimeToIso('2026-11-20', '20:00', 'Asia/Ho_Chi_Minh')).toBe('2026-11-20T13:00:00.000Z')
    expect(zonedTimeToIso('2026-11-20', '22:00', 'Asia/Ho_Chi_Minh')).toBe('2026-11-20T15:00:00.000Z')
    expect(zonedTimeToIso('2026-11-21', '00:30', 'Asia/Ho_Chi_Minh')).toBe('2026-11-20T17:30:00.000Z')
  })

  it('tôn trọng múi giờ khác và giờ mùa hè', () => {
    expect(zonedTimeToIso('2026-07-01', '12:00', 'Europe/London')).toBe('2026-07-01T11:00:00.000Z')
    expect(zonedTimeToIso('2026-01-01', '12:00', 'Europe/London')).toBe('2026-01-01T12:00:00.000Z')
    expect(zonedTimeToIso('2026-11-20', '23:59:59', 'UTC')).toBe('2026-11-20T23:59:59.000Z')
  })

  it('từ chối đầu vào sai', () => {
    expect(zonedTimeToIso('2026-02-31', '10:00', 'Asia/Ho_Chi_Minh')).toBeNull()
    expect(zonedTimeToIso('20/11/2026', '10:00')).toBeNull()
    expect(zonedTimeToIso('2026-11-20', '25')).toBeNull()
  })

  it('đổi ngược để điền sẵn form', () => {
    expect(isoToZonedInput('2026-11-20T13:00:00Z', 'Asia/Ho_Chi_Minh')).toEqual({ date: '2026-11-20', time: '20:00' })
    expect(isoToZonedInput('2026-11-20T17:30:00Z', 'Asia/Ho_Chi_Minh')).toEqual({ date: '2026-11-21', time: '00:30' })
    expect(isoToZonedInput(null)).toEqual({ date: '', time: '' })
  })
})
