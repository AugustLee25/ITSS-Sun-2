import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type SupportRequest } from '@/api/types'
import { AN, GROUP_ID, MINH, taskDetail } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const request = (overrides: Partial<SupportRequest> = {}): SupportRequest => ({
  id: 'sr-1',
  taskId: 'task-1',
  taskTitle: 'Thiết kế API',
  sender: AN,
  obstacleType: 'TECHNICAL',
  status: 'OPEN',
  createdAt: '2026-11-18T03:00:00Z',
  updatedAt: '2026-11-18T03:00:00Z',
  groupId: GROUP_ID,
  roundId: 'round-1',
  description: 'Chưa kết nối được DB test',
  neededSupport: 'Cần người xem giúp cấu hình',
  impact: 'Có thể trễ 1 ngày',
  expectedDate: '2026-11-21',
  decision: null,
  leadResponse: null,
  reviewedBy: null,
  reviewedAt: null,
  publishedHelpRequestIds: [],
  version: 0,
  allowedActions: [],
  ...overrides,
})

describe('S07 — Báo khó khăn', () => {
  it('thành viên gửi riêng cho TL: giữ nguyên nội dung, gửi roundId, rồi theo dõi tình trạng', async () => {
    const api = mockApi([
      ['GET', '/tasks/task-1', taskDetail()],
      ['POST', '/tasks/task-1/support-requests', request()],
      ['GET', '/support-requests/sr-1', request()],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/tasks/task-1/difficulty`)

    await screen.findByLabelText(/Mô tả ngắn/)
    await user.click(screen.getByRole('button', { name: 'Gửi TL xem xét' }))
    expect(await screen.findByText('Mô tả từ 10 đến 2000 ký tự')).toBeInTheDocument()
    expect(api.writes('POST', '/tasks/task-1/support-requests')).toHaveLength(0)

    await user.type(screen.getByLabelText(/Mô tả ngắn/), 'Chưa kết nối được DB test')
    await user.type(screen.getByLabelText(/Phần cần hỗ trợ/), 'Cần người xem giúp cấu hình')
    await user.click(screen.getByRole('button', { name: 'Gửi TL xem xét' }))

    await waitFor(() => expect(api.writes('POST', '/tasks/task-1/support-requests')).toHaveLength(1))
    expect(api.writes('POST', '/tasks/task-1/support-requests')[0].body).toMatchObject({
      obstacleType: 'TECHNICAL',
      description: 'Chưa kết nối được DB test',
      neededSupport: 'Cần người xem giúp cấu hình',
      impact: null,
      roundId: 'round-1',
    })
    expect(await screen.findByText('TL chưa phản hồi. Bạn sẽ nhận thông báo khi có phản hồi.')).toBeInTheDocument()
  })

  it('TL xử lý: phản hồi người gửi (kèm version) và đăng công khai chỉ qua form S08', async () => {
    const api = mockApi([
      ['GET', '/support-requests/sr-1', request({ allowedActions: ['DECIDE', 'PUBLISH_HELP'] })],
      ['POST', '/support-requests/sr-1/decision', request({ status: 'RESOLVED', leadResponse: 'Đã trao đổi riêng' })],
      ['GET', `/groups/${GROUP_ID}/risk-reports`, { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/difficulty/sr-1`)

    expect(await screen.findByText('Chưa kết nối được DB test')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đăng task cần người làm thay' })).toHaveAttribute(
      'href',
      `/groups/${GROUP_ID}/help?create=1&taskId=task-1&supportRequestId=sr-1`,
    )
    await user.type(screen.getByLabelText(/Phản hồi cho người gửi/), 'Đã trao đổi riêng')
    await user.click(screen.getByRole('button', { name: 'Đã xử lý, không cần đăng hỗ trợ' }))

    await waitFor(() => expect(api.writes('POST', '/support-requests/sr-1/decision')).toHaveLength(1))
    expect(api.writes('POST', '/support-requests/sr-1/decision')[0].body).toEqual({ action: 'RESOLVED_PRIVATELY', response: 'Đã trao đổi riêng', version: 0 })
  })

  it('người không có quyền xem đề nghị riêng nhận thông báo, không lộ nội dung', async () => {
    mockApi([
      ['GET', '/support-requests/sr-1', () => apiError(403, 'FORBIDDEN', 'Không có quyền')],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    renderApp(`/groups/${GROUP_ID}/difficulty/sr-1`)
    expect(await screen.findByText('Bạn không xem được đề nghị này')).toBeInTheDocument()
    expect(screen.queryByText('Chưa kết nối được DB test')).not.toBeInTheDocument()
  })
})
