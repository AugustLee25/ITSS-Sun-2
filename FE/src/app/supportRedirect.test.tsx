import { screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole } from '@/api/types'
import { AN, GROUP_ID, taskDetail } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

/** Solution 2 bỏ màn "My Support Work" và màn nghiệm thu riêng: link cũ chỉ còn chuyển hướng về chi tiết task (S05). */
describe('link cũ của màn thực hiện/nghiệm thu hỗ trợ', () => {
  const taskPage = `/groups/${GROUP_ID}/tasks/task-1`

  it('[S2-C8] /help/:helpRequestId/work → chi tiết task (không có màn My Support Work)', async () => {
    mockApi([
      ['GET', '/help-requests/help-1', { id: 'help-1', task: { id: 'task-1', title: 'Thiết kế API' }, work: { id: 'work-1' } }],
      ['GET', '/tasks/task-1', taskDetail({ allowedActions: [] })],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const { router } = renderApp(`/groups/${GROUP_ID}/help/help-1/work`)
    await waitFor(() => expect(router.state.location.pathname).toBe(taskPage))
    expect(await screen.findByRole('heading', { level: 1, name: 'Thiết kế API' })).toBeInTheDocument()
  })

  it('[S2-D13] /help/:id/review (link nghiệm thu cũ) → cùng task, nơi TL duyệt như task thường', async () => {
    mockApi([
      ['GET', '/help-requests/help-1', { id: 'help-1', task: { id: 'task-1', title: 'Thiết kế API' }, work: null }],
      ['GET', '/tasks/task-1', taskDetail({ allowedActions: [] })],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const { router } = renderApp(`/groups/${GROUP_ID}/help/help-1/review`)
    await waitFor(() => expect(router.state.location.pathname).toBe(taskPage))
  })

  it('[S2-D13] khóa trên URL là workId (link từ thông báo cũ): tra phần việc rồi chuyển về task', async () => {
    mockApi([
      ['GET', '/support-works/work-9', { id: 'work-9', groupId: GROUP_ID, task: { id: 'task-1', title: 'Thiết kế API' } }],
      ['GET', '/tasks/task-1', taskDetail({ allowedActions: [] })],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const { router } = renderApp(`/groups/${GROUP_ID}/help/work-9/work`)
    await waitFor(() => expect(router.state.location.pathname).toBe(taskPage))
  })

  it('[S2-D13] không tìm thấy yêu cầu/phần việc → trang không tìm thấy thân thiện, không vòng lặp chuyển hướng', async () => {
    mockApi([
      ['GET', '/help-requests/missing', apiError(404, 'NOT_FOUND', 'x')],
      ['GET', '/support-works/missing', apiError(404, 'NOT_FOUND', 'x')],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const { router } = renderApp(`/groups/${GROUP_ID}/help/missing/work`)
    expect(await screen.findByText('Không tìm thấy nội dung')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/groups/${GROUP_ID}/help/missing/work`)
  })
})
