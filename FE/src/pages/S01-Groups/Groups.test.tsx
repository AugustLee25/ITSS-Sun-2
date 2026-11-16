import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole } from '@/api/types'
import { AN, GROUP_ID, groupCard } from '@/test/fixtures'
import { apiError, mockApi, renderApp } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const base = (groups: unknown) =>
  [
    ['GET', '/auth/me', { userId: AN.userId, name: AN.name, email: 'an@example.com', createdAt: '2026-11-01T00:00:00Z' }],
    ['GET', '/notifications/unread-count', { total: 0, actionRequired: 0, byGroup: [] }],
    ['GET', '/groups', groups],
  ] as Parameters<typeof mockApi>[0]

describe('S01 — Nhóm của tôi', () => {
  it('chưa có nhóm: empty state với hai CTA (tạo nhóm / tham gia bằng mã)', async () => {
    mockApi(base({ items: [] }))
    renderApp('/groups')
    expect(await screen.findByRole('heading', { name: 'Bạn chưa ở trong nhóm nào' })).toBeInTheDocument()
    const create = screen.getAllByRole('link', { name: 'Tạo nhóm' })
    const join = screen.getAllByRole('link', { name: 'Tham gia bằng mã' })
    expect(create.length).toBeGreaterThanOrEqual(2)
    expect(create[0]).toHaveAttribute('href', '/groups/new')
    expect(join[0]).toHaveAttribute('href', '/groups/join')
  })

  it('thẻ nhóm: tên, môn, số thành viên, deadline, nhãn tóm tắt do server, chạm cả thẻ → S03; không có biểu đồ tiến độ', async () => {
    mockApi(
      base({
        items: [
          groupCard({ myRole: GroupRole.LEAD }),
          groupCard({ id: 'grp-2', name: 'Đồ án Web', courseName: null, attention: { count: 0, label: 'Không có việc cần chú ý' }, unreadNotifications: 0 }),
        ],
      }),
    )
    renderApp('/groups')
    const link = await screen.findByRole('link', { name: 'Bài tập lớn CSDL' })
    expect(link).toHaveAttribute('href', `/groups/${GROUP_ID}`)
    expect(screen.getByText('1 checkpoint cần phản hồi')).toBeInTheDocument()
    expect(screen.getByText('Không có việc cần chú ý')).toBeInTheDocument()
    expect(screen.getAllByText('20/12/2026', { selector: 'time' })).toHaveLength(2)
    expect(screen.getByText('Trưởng nhóm')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem 3 thông báo chưa đọc' })).toHaveAttribute('href', '/notifications')
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('lỗi mạng/server: thông báo + nút thử lại tải lại danh sách', async () => {
    let fail = true
    mockApi(base(() => (fail ? apiError(429, 'RATE_LIMITED', 'boom') : { items: [groupCard()] })))
    const user = userEvent.setup()
    renderApp('/groups')
    expect(await screen.findByText('Chưa tải được danh sách nhóm')).toBeInTheDocument()
    fail = false
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(await screen.findByRole('link', { name: 'Bài tập lớn CSDL' })).toBeInTheDocument()
  })
})
