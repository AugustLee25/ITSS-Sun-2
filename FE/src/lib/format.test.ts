import { describe, expect, it } from 'vitest'
import { formatDate, formatDateTime, formatPointsLabel, formatRelativeTime, formatTime } from './format'

describe('formatDateTime (dd/MM/yyyy, HH:mm theo múi giờ nhóm)', () => {
  it('đổi UTC sang múi giờ Việt Nam (UTC+7)', () => {
    expect(formatDateTime('2026-11-15T10:30:00Z', 'Asia/Ho_Chi_Minh')).toBe('15/11/2026, 17:30')
  })

  it('đổi UTC sang múi giờ Nhật (UTC+9) và qua ngày', () => {
    expect(formatDateTime('2026-11-15T20:05:00Z', 'Asia/Tokyo')).toBe('16/11/2026, 05:05')
  })

  it('giữa đêm hiển thị 00:xx chứ không phải 24:xx', () => {
    expect(formatDateTime('2026-11-14T17:05:00Z', 'Asia/Ho_Chi_Minh')).toBe('15/11/2026, 00:05')
  })

  it('dùng múi giờ mặc định nếu múi giờ không hợp lệ, trả "—" nếu thời gian không hợp lệ', () => {
    expect(formatDateTime('2026-11-15T10:30:00Z', 'Not/AZone')).toBe('15/11/2026, 17:30')
    expect(formatDateTime('khong-hop-le')).toBe('—')
    expect(formatDateTime(null)).toBe('—')
  })

  it('formatDate / formatTime tách từng phần', () => {
    expect(formatDate('2026-11-15T10:30:00Z', 'Asia/Ho_Chi_Minh')).toBe('15/11/2026')
    expect(formatTime('2026-11-15T10:30:00Z', 'Asia/Ho_Chi_Minh')).toBe('17:30')
  })
})

describe('formatPointsLabel (PointAmount do server định dạng sẵn)', () => {
  const amount = { pointUnits: 100_000, percentLabel: '10,0000' }
  const delta = { pointUnits: 40_000, percentLabel: '+4,0000' }

  it('ghép percentLabel với đơn vị "điểm %", không tự định dạng lại', () => {
    expect(formatPointsLabel(amount)).toBe('10,0000 điểm %')
    expect(formatPointsLabel(delta)).toBe('+4,0000 điểm %')
    expect(formatPointsLabel({ pointUnits: -40_000, percentLabel: '-4,0000' })).toBe('-4,0000 điểm %')
  })

  it('signed thêm "+" cho lượng dương chưa có dấu (HELP_PUBLISHED), không thêm cho 0 hoặc đã có dấu', () => {
    expect(formatPointsLabel(amount, { signed: true })).toBe('+10,0000 điểm %')
    expect(formatPointsLabel(delta, { signed: true })).toBe('+4,0000 điểm %')
    expect(formatPointsLabel({ pointUnits: 0, percentLabel: '0,0000' }, { signed: true })).toBe('0,0000 điểm %')
  })

  it('tùy chọn bỏ đơn vị; thiếu dữ liệu → "—"', () => {
    expect(formatPointsLabel(amount, { unit: false })).toBe('10,0000')
    expect(formatPointsLabel(null)).toBe('—')
  })
})

describe('formatRelativeTime', () => {
  const now = '2026-11-15T10:00:00Z'
  it('phút / giờ / ngày trước', () => {
    expect(formatRelativeTime('2026-11-15T09:55:00Z', { now })).toBe('5 phút trước')
    expect(formatRelativeTime('2026-11-15T07:00:00Z', { now })).toBe('3 giờ trước')
    expect(formatRelativeTime('2026-11-12T10:00:00Z', { now })).toBe('3 ngày trước')
  })

  it('quá 7 ngày dùng ngày tuyệt đối theo múi giờ', () => {
    expect(formatRelativeTime('2026-10-01T10:00:00Z', { now, timeZone: 'Asia/Ho_Chi_Minh' })).toBe('01/10/2026')
  })
})
