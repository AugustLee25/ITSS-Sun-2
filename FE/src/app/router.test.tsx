import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setToken } from '@/api/session'
import { AppProviders } from './providers'
import { createQueryClient } from './queryClient'
import { buildLoginUrl, resolvePostLoginPath } from './redirect'
import { createTestRouter } from './router'
import { paths } from './routes'

const USER = { userId: 'u1', email: 'an@example.com', name: 'Nguyễn Văn An', createdAt: '2026-11-01T00:00:00Z' }
const GROUP = {
  id: 'g1',
  name: 'Nhóm Lập trình Web',
  courseName: null,
  startDate: '2026-11-01',
  deadline: '2026-12-31',
  timezone: 'Asia/Ho_Chi_Minh',
  memberCount: 5,
  leader: { userId: 'u1', name: 'Nguyễn Văn An' },
  myRole: 'LEAD',
  createdAt: '2026-11-01T00:00:00Z',
  version: 1,
}
const CARD = { id: 'g1', name: 'Nhóm Lập trình Web', courseName: null, memberCount: 5, deadline: '2026-12-31', myRole: 'LEAD', attention: { count: 0, label: '' }, unreadNotifications: 0, lastActivityAt: null }

function json(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

/** Giả lập BE: user đăng nhập hợp lệ, nhóm g1 là thành viên, g403 bị cấm, g404 không có. */
function stubApi() {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/auth/me')) return json(USER)
    if (url.endsWith('/notifications/unread-count')) return json({ total: 3, actionRequired: 1, byGroup: [] })
    if (url.endsWith('/groups/g1')) return json(GROUP)
    if (url.endsWith('/groups/g403')) return json({ code: 'FORBIDDEN', message: 'no' }, 403)
    if (url.endsWith('/groups/g404')) return json({ code: 'NOT_FOUND', message: 'no' }, 404)
    if (url.endsWith('/groups')) return json({ items: [CARD] })
    return json({ code: 'NOT_FOUND', message: 'no' }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderAt(path: string) {
  const router = createTestRouter([path])
  render(
    <AppProviders queryClient={createQueryClient()}>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return router
}

beforeEach(() => {
  stubApi()
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('redirect helpers', () => {
  it('buildLoginUrl giữ đích cũ và mã lời mời; bỏ qua đích mặc định', () => {
    expect(buildLoginUrl({ pathname: '/groups/join', search: '?invite=ABC' })).toBe(
      '/login?next=%2Fgroups%2Fjoin%3Finvite%3DABC&invite=ABC',
    )
    expect(buildLoginUrl({ pathname: '/join/K7P4Q9XD' })).toBe('/login?next=%2Fjoin%2FK7P4Q9XD&invite=K7P4Q9XD')
    expect(buildLoginUrl({ pathname: '/groups/g1/tasks/7' })).toBe('/login?next=%2Fgroups%2Fg1%2Ftasks%2F7')
    expect(buildLoginUrl({ pathname: '/groups' })).toBe('/login')
  })

  it('resolvePostLoginPath: next an toàn → invite → S01; chặn open-redirect', () => {
    expect(resolvePostLoginPath('?next=%2Fgroups%2Fg1%2Ftasks')).toBe('/groups/g1/tasks')
    expect(resolvePostLoginPath('?invite=ABC')).toBe('/join/ABC')
    expect(resolvePostLoginPath('?next=https%3A%2F%2Fevil.example')).toBe('/groups')
    expect(resolvePostLoginPath('?next=%2F%2Fevil.example')).toBe('/groups')
    expect(resolvePostLoginPath('')).toBe('/groups')
  })
})

describe('route bảo vệ', () => {
  it('chưa đăng nhập → S00, giữ ?invite= và đích cũ', async () => {
    const router = renderAt('/join/XYZ')
    await screen.findByRole('tab', { name: 'Đăng nhập' })
    expect(router.state.location.pathname).toBe('/login')
    const params = new URLSearchParams(router.state.location.search)
    expect(params.get('invite')).toBe('XYZ')
    expect(params.get('next')).toBe('/join/XYZ')
  })

  it('chưa đăng nhập mở thẳng route nhóm → S00 với next là route đó', async () => {
    const router = renderAt('/groups/g1/tasks/t9')
    await screen.findByRole('tab', { name: 'Đăng nhập' })
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe('/groups/g1/tasks/t9')
  })

  it('đã đăng nhập → vào được trang; "/" chuyển tới S01', async () => {
    setToken('valid')
    const router = renderAt('/')
    await screen.findByRole('heading', { name: 'Nhóm của tôi' })
    expect(router.state.location.pathname).toBe('/groups')
  })

  it('token hết hạn (401) → về S00 giữ đích cũ', async () => {
    setToken('expired')
    vi.stubGlobal('fetch', vi.fn(() => json({ code: 'UNAUTHORIZED', message: 'x' }, 401)))
    const router = renderAt('/groups/g1/help')
    await screen.findByRole('tab', { name: 'Đăng nhập' })
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe('/groups/g1/help')
  })
})

describe('route nhóm, 403/404', () => {
  beforeEach(() => setToken('valid'))

  it('thành viên nhóm thấy khung nhóm với 4 mục điều hướng và trang S03', async () => {
    renderAt('/groups/g1')
    await screen.findByRole('heading', { name: 'Tổng quan nhóm' })
    const navs = screen.getAllByRole('navigation', { name: 'Điều hướng chính' })
    expect(navs.length).toBeGreaterThan(0)
    for (const label of ['Tổng quan', 'Công việc', 'Hỗ trợ', 'Điểm & Ghi nhận']) {
      expect(navs[0]).toHaveTextContent(label)
    }
    expect(screen.getAllByRole('link', { name: 'Tổng quan' })[0]).toHaveAttribute('aria-current', 'page')
  })

  it('không phải thành viên (403) → trang giải thích quyền + đường về danh sách nhóm', async () => {
    renderAt('/groups/g403/tasks')
    await screen.findByText('Bạn chưa có quyền xem nội dung này')
    expect(screen.getByRole('link', { name: 'Về danh sách nhóm' })).toHaveAttribute('href', '/groups')
  })

  it('nhóm không tồn tại (404) → trang không tìm thấy thân thiện', async () => {
    renderAt('/groups/g404')
    await screen.findByText('Không tìm thấy nội dung')
  })

  it('URL không khớp route → 404 toàn trang; URL lạ trong nhóm → 404 trong khung nhóm', async () => {
    renderAt('/khong-co-trang-nay')
    await screen.findByText('Không tìm thấy nội dung')
    expect(screen.getByRole('link', { name: 'Về danh sách nhóm' })).toBeInTheDocument()
  })

  it('404 trong nhóm có nút về trang chủ nhóm', async () => {
    renderAt('/groups/g1/khong-co')
    await screen.findByText('Không tìm thấy nội dung')
    expect(screen.getByRole('link', { name: 'Về trang chủ nhóm' })).toHaveAttribute('href', '/groups/g1')
  })

  it('mọi màn hình S02–S13 mở trực tiếp được bằng URL', async () => {
    const cases: Array<[string, string]> = [
      ['/groups/new', 'Tạo / Tham gia nhóm'],
      ['/groups/join', 'Tạo / Tham gia nhóm'],
      ['/join/K7P4Q9XD', 'Tạo / Tham gia nhóm'],
      ['/notifications', 'Thông báo'],
      ['/groups/g1/tasks', 'Công việc'],
      ['/groups/g1/help', 'Cùng nhau hỗ trợ'],
      ['/groups/g1/help/r1', 'Không tìm thấy nội dung'],
      ['/groups/g1/help/r1/work', 'Thực hiện & Bàn giao hỗ trợ'],
      ['/groups/g1/help/r1/review', 'Xác nhận kết quả & Ghi điểm'],
      ['/groups/g1/points', 'Điểm của tôi & Sổ đóng góp'],
    ]
    for (const [path, heading] of cases) {
      const router = createTestRouter([path])
      const view = render(
        <AppProviders queryClient={createQueryClient()}>
          <RouterProvider router={router} />
        </AppProviders>,
      )
      await screen.findByRole('heading', { name: heading })
      view.unmount()
    }
  })
})

describe('route modal S06/S07', () => {
  beforeEach(() => setToken('valid'))

  it('mở trực tiếp link S06: modal có tiêu đề, màn gọi (S03) nằm phía sau; Esc về trang chủ nhóm', async () => {
    const router = renderAt('/groups/g1/checkpoints/c1')
    const dialog = await screen.findByRole('dialog', { name: 'Cập nhật checkpoint' })
    expect(dialog).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Tổng quan nhóm' })).toBeInTheDocument()

    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(router.state.location.pathname).toBe('/groups/g1'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('S07 mở từ S03 rồi đóng → quay về màn gọi', async () => {
    const router = renderAt('/groups/g1')
    await screen.findByRole('heading', { name: 'Tổng quan nhóm' })
    await router.navigate(paths.difficulty('g1', 'sr1'))
    await screen.findByRole('dialog', { name: 'Xử lý đề nghị hỗ trợ' })
    await userEvent.click(screen.getByRole('button', { name: 'Đóng hộp thoại' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/groups/g1'))
  })
})
