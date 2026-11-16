import { describe, expect, it } from 'vitest'
import { NotificationType } from '@/api/types'
import { NOTIFICATION_ICONS } from '@/components/NotificationItem/notificationTypes'
import { defaultT, type MessageKey } from '@/i18n'
import { wantsIdempotencyKey } from '@/api/idempotency'
import { paths, pathForTarget } from './routes'

describe('pathForTarget (ScreenTarget do server gợi ý → URL)', () => {
  it('map các màn hình chính, kể cả S06/S07 dạng modal', () => {
    expect(pathForTarget({ screen: 'S05', params: { groupId: 'g', taskId: 't' } })).toBe('/groups/g/tasks/t')
    expect(pathForTarget({ screen: 'S06', params: { groupId: 'g', cycleId: 'c' } })).toBe('/groups/g/checkpoints/c')
    expect(pathForTarget({ screen: 'S06', params: { groupId: 'g', taskId: 't' } })).toBe('/groups/g/tasks/t/checkpoint')
    expect(pathForTarget({ screen: 'S07', params: { groupId: 'g', supportRequestId: 's' } })).toBe('/groups/g/difficulty/s')
    expect(pathForTarget({ screen: 'S09', params: { groupId: 'g', helpRequestId: 'h' } })).toBe('/groups/g/help/h')
    expect(pathForTarget({ screen: 'S11', params: { groupId: 'g', helpRequestId: 'h' } })).toBe('/groups/g/help/h/review')
    expect(pathForTarget({ screen: 'S12', params: { groupId: 'g' } })).toBe(paths.points('g'))
  })

  it('thiếu tham số → về trang chủ nhóm / S01', () => {
    expect(pathForTarget({ screen: 'S05', params: { groupId: 'g' } })).toBe('/groups/g')
    expect(pathForTarget({ screen: 'S05', params: {} })).toBe('/groups')
  })
})

describe('thông báo & idempotency theo hợp đồng', () => {
  it('mọi NotificationType đều có biểu tượng và tiêu đề/CTA mặc định', () => {
    for (const type of Object.values(NotificationType)) {
      expect(NOTIFICATION_ICONS[type]).toBeTruthy()
      expect(defaultT(`notifications.types.${type}.title` as MessageKey)).not.toContain('notifications.types')
      expect(defaultT(`notifications.types.${type}.cta` as MessageKey)).not.toContain('notifications.types')
    }
  })

  it('wantsIdempotencyKey khớp mẫu đường dẫn có tham số', () => {
    expect(wantsIdempotencyKey('POST', '/plans/3f2a/review')).toBe(true)
    expect(wantsIdempotencyKey('POST', '/groups/g1/help-requests')).toBe(true)
    expect(wantsIdempotencyKey('GET', '/groups/g1/help-requests')).toBe(false)
    expect(wantsIdempotencyKey('POST', '/auth/login')).toBe(false)
  })
})
