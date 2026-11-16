import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type NotificationItem } from '@/api/types'
import { AN, GROUP_ID } from '@/test/fixtures'
import { mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const reminder: NotificationItem = {
  id: 'n-1',
  type: 'CHECKPOINT_REMINDER',
  priority: 'ACTION_REQUIRED',
  groupId: GROUP_ID,
  groupName: 'Bài tập lớn CSDL',
  title: 'Đến lúc cập nhật ngắn về công việc rồi!',
  message: 'Kỳ 3 chốt lúc 22:00 Thứ Sáu 20/11 (còn 7 giờ).',
  subjectLabel: 'Kỳ 3',
  entityType: 'CHECKPOINT_CYCLE',
  entityId: 'cyc-3',
  cycleId: 'cyc-3',
  target: { screen: 'S06', params: { groupId: GROUP_ID, cycleId: 'cyc-3' } },
  isRead: false,
  readAt: null,
  createdAt: '2026-11-20T08:00:00Z',
}
const page = (items: NotificationItem[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 })
const unreadCalls = (api: ReturnType<typeof mockApi>) => api.calls.filter((c) => c.path === '/notifications/unread-count').length

describe('S13 — Thông báo', () => {
  it('tab theo ưu tiên + chưa đọc; mở thông báo = gọi open (làm mới chuông) rồi đi tới đích server trả', async () => {
    const api = mockApi([
      ['GET', '/notifications', page([reminder])],
      ['POST', '/notifications/n-1/open', { notification: { ...reminder, isRead: true }, available: true, target: reminder.target, unavailableMessage: null, fallback: null }],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp('/notifications')

    expect(await screen.findByText('Đến lúc cập nhật ngắn về công việc rồi!')).toBeInTheDocument()
    expect(screen.getByText('Bài tập lớn CSDL · Kỳ 3')).toBeInTheDocument()
    expect(api.calls.find((c) => c.path === '/notifications')?.search.get('tab')).toBe('ACTION_REQUIRED')

    await user.click(screen.getByRole('tab', { name: 'Thông tin' }))
    await waitFor(() => expect(api.calls.some((c) => c.path === '/notifications' && c.search.get('tab') === 'INFO')).toBe(true))
    await user.click(screen.getByRole('tab', { name: 'Cần hành động' }))
    await user.click(await screen.findByRole('checkbox', { name: 'Chỉ hiện chưa đọc' }))
    await waitFor(() => expect(api.calls.some((c) => c.search.get('unreadOnly') === 'true')).toBe(true))

    const before = unreadCalls(api)
    await user.click(await screen.findByRole('button', { name: /Đến lúc cập nhật ngắn/ }))
    await waitFor(() => expect(api.writes('POST', '/notifications/n-1/open')).toHaveLength(1))
    await waitFor(() => expect(unreadCalls(api)).toBeGreaterThan(before))
    expect(await screen.findByRole('dialog', { name: 'Cập nhật checkpoint' })).toBeInTheDocument()
  })

  it('đích không còn: báo "Nội dung này không còn khả dụng" rồi về danh sách thích hợp', async () => {
    mockApi([
      ['GET', '/notifications', page([{ ...reminder, type: 'PLAN_APPROVED', target: { screen: 'S05', params: { groupId: GROUP_ID, taskId: 'gone' } } }])],
      [
        'POST',
        '/notifications/n-1/open',
        { notification: reminder, available: false, target: reminder.target, unavailableMessage: 'Nội dung này không còn khả dụng', fallback: { screen: 'S04', params: { groupId: GROUP_ID } } },
      ],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const { router } = renderApp('/notifications')
    await userEvent.setup().click(await screen.findByRole('button', { name: /Đến lúc cập nhật ngắn/ }))
    expect(await screen.findByText('Nội dung này không còn khả dụng')).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.pathname).toBe(`/groups/${GROUP_ID}/tasks`))
  })

  it('đánh dấu tất cả đã đọc chỉ đổi trạng thái đọc (theo tab đang xem) và làm mới chuông', async () => {
    const api = mockApi([
      ['GET', '/notifications', page([reminder])],
      ['POST', '/notifications/read-all', { updated: 1 }],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    renderApp('/notifications')
    await screen.findByText('Đến lúc cập nhật ngắn về công việc rồi!')
    const before = unreadCalls(api)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }))
    await waitFor(() => expect(api.writes('POST', '/notifications/read-all')).toHaveLength(1))
    expect(api.writes('POST', '/notifications/read-all')[0].body).toEqual({ tab: 'ACTION_REQUIRED' })
    await waitFor(() => expect(unreadCalls(api)).toBeGreaterThan(before))
  })
})
