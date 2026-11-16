import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuthResponse, InvitePreview } from '@/api/types'
import { AN, INVITE, MINH, profileOf } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const PREVIEW: InvitePreview = {
  code: INVITE.code,
  status: 'ACTIVE',
  groupName: 'Bài tập lớn CSDL',
  courseName: 'Cơ sở dữ liệu',
  leader: MINH,
  memberCount: 5,
  expiresAt: INVITE.expiresAt,
  alreadyMember: false,
  groupId: null,
}

const AUTH: AuthResponse = {
  accessToken: 'tok-an',
  tokenType: 'Bearer',
  expiresIn: 28800,
  expiresAt: '2026-11-15T18:00:00Z',
  user: profileOf(AN),
}

describe('S00 — đăng nhập', () => {
  it('giữ lời mời + đích cũ, Google tắt có giải thích, lỗi đăng nhập ở lại S00, thành công vào S02 xác nhận tham gia', async () => {
    const api = mockApi([
      ['GET', '/auth/config', { googleEnabled: false, passwordMinLength: 8, demoMode: false }],
      ['GET', `/invites/${INVITE.code}`, PREVIEW],
      [
        'POST',
        '/auth/login',
        (call) =>
          (call.body as { password: string }).password === 'sai-mat-khau'
            ? apiError(401, 'INVALID_CREDENTIALS', 'Sai thông tin')
            : AUTH,
      ],
      ...shellRoutes(AN),
    ])
    const user = userEvent.setup()
    renderApp(`/login?next=${encodeURIComponent(`/join/${INVITE.code}`)}&invite=${INVITE.code}`, { signedIn: false })

    expect(await screen.findByText(/Bạn đang được mời vào nhóm Bài tập lớn CSDL/)).toBeInTheDocument()
    const google = screen.getByRole('button', { name: /Tiếp tục với Google/ })
    expect(google).toBeDisabled()
    expect(screen.getByText(/Đăng nhập Google chưa được bật/)).toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Email/), 'an@example.com')
    await user.type(screen.getByLabelText(/^Mật khẩu/), 'sai-mat-khau')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))
    expect(await screen.findByText(/Email hoặc mật khẩu chưa đúng/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'HANARE' })).toBeInTheDocument()

    await user.clear(screen.getByLabelText(/^Mật khẩu/))
    await user.type(screen.getByLabelText(/^Mật khẩu/), 'mat-khau-dung')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))

    // S02 chế độ tham gia: tự kiểm tra lời mời, hiện nhóm và nút xác nhận
    expect(await screen.findByRole('button', { name: 'Tham gia nhóm' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Bài tập lớn CSDL' })).toBeInTheDocument()
    expect(api.writes('POST', '/auth/login')).toHaveLength(2)
  })

  it('đăng ký: kiểm tra độ dài mật khẩu theo cấu hình server và báo email đã tồn tại ở trường email', async () => {
    mockApi([
      ['GET', '/auth/config', { googleEnabled: false, passwordMinLength: 10, demoMode: false }],
      ['POST', '/auth/register', apiError(409, 'EMAIL_ALREADY_REGISTERED', 'Email đã tồn tại')],
      ...shellRoutes(AN),
    ])
    const user = userEvent.setup()
    renderApp('/login', { signedIn: false })

    await user.click(await screen.findByRole('tab', { name: 'Tạo tài khoản' }))
    await user.type(screen.getByLabelText(/^Họ tên/), 'An')
    await user.type(screen.getByLabelText(/^Email/), 'an@example.com')
    await user.type(screen.getByLabelText(/^Mật khẩu/), 'ngan123')
    await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }))
    expect(await screen.findByText(/Mật khẩu tối thiểu 10 ký tự/)).toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Mật khẩu/), '4567890')
    await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }))
    await waitFor(() => expect(screen.getByText(/Email này đã có tài khoản/)).toBeInTheDocument())
  })
})
