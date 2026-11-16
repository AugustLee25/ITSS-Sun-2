import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, SupportWorkAction, SupportWorkStatus, type SupportWork } from '@/api/types'
import { BINH, GROUP_ID, MINH } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes, type ApiRoute } from '@/test/renderApp'
import { HELP_ID, WORK_ID, WORK_PAGE, submittedWork, supportWork } from './workFixtures'

afterEach(() => vi.unstubAllGlobals())

/** `current` đổi sau khi ghi để mô phỏng BE trả trạng thái mới ở lần tải lại. */
function routes(role: GroupRole, state: { current: SupportWork }, extra: ApiRoute[] = []): ApiRoute[] {
  return [
    ['GET', `/help-requests/${HELP_ID}`, { work: { id: WORK_ID } }],
    ['GET', `/support-works/${WORK_ID}`, () => state.current],
    ...extra,
    ...shellRoutes(role === GroupRole.LEAD ? MINH : BINH, role),
  ]
}

describe('S10 — Helper bàn giao', () => {
  it('hiện phạm vi, điểm đã chốt "Chưa ghi điểm"; nộp kết quả gửi Idempotency-Key một lần và phân biệt chờ xác nhận với đã công nhận', async () => {
    const state = { current: supportWork() }
    const api = mockApi(
      routes(GroupRole.MEMBER, state, [
        [
          'POST',
          `/support-works/${WORK_ID}/submissions`,
          () => {
            state.current = submittedWork({ allowedActions: [] })
            return { submission: state.current.submissions[0], work: state.current }
          },
        ],
      ]),
    )
    const user = userEvent.setup()
    renderApp(WORK_PAGE)

    expect(await screen.findByRole('heading', { level: 1, name: 'Thiết kế API (toàn bộ task)' })).toBeInTheDocument()
    const info = screen.getByRole('heading', { name: 'Phạm vi đã nhận' }).closest('section') as HTMLElement
    expect(within(info).getByText('10,0000 điểm %')).toBeInTheDocument()
    expect(within(info).getByText('Chưa ghi điểm')).toBeInTheDocument()
    expect(within(info).getByText('Hoàn thiện 12 endpoint nhóm/task, kèm OpenAPI')).toBeInTheDocument()

    // Thiếu dữ liệu: báo lỗi cạnh trường, không gửi.
    await user.click(screen.getByRole('button', { name: 'Gửi kết quả' }))
    expect(await screen.findAllByText('Nhập đường dẫn https:// hợp lệ')).not.toHaveLength(0)
    expect(screen.getByText('Tóm tắt cần từ 10 đến 1000 ký tự')).toBeInTheDocument()
    expect(api.writes('POST', /submissions/)).toHaveLength(0)

    await user.type(screen.getByLabelText(/Link kết quả 1/), 'https://github.com/x/y/pull/9')
    await user.type(screen.getByLabelText(/Tóm tắt phần việc đã làm/), 'Đã xong 12 endpoint và OpenAPI')
    await user.type(screen.getByLabelText('Giờ'), '1')
    await user.type(screen.getByLabelText('Phút'), '35')
    await user.dblClick(screen.getByRole('button', { name: 'Gửi kết quả' })) // bấm đúp

    expect(await screen.findByText('Cảm ơn bạn đã hoàn thành phần việc này!')).toBeInTheDocument()
    expect(screen.getAllByText('Đã gửi kết quả — chờ xác nhận').length).toBeGreaterThan(0)
    expect(screen.queryByText('Đóng góp đã được công nhận')).not.toBeInTheDocument()
    const writes = api.writes('POST', `/support-works/${WORK_ID}/submissions`)
    expect(writes).toHaveLength(1)
    expect(writes[0]?.headers.get('Idempotency-Key')).toBeTruthy()
    expect(writes[0]?.body).toEqual({
      resultNote: 'Đã xong 12 endpoint và OpenAPI',
      evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/9' }],
      actualMinutes: 95,
    })
    expect(screen.queryByRole('button', { name: 'Gửi kết quả' })).not.toBeInTheDocument()
  })

  it('lỗi từ server giữ nguyên dữ liệu đã nhập và hiện lý do', async () => {
    const state = { current: supportWork() }
    mockApi(
      routes(GroupRole.MEMBER, state, [
        ['POST', `/support-works/${WORK_ID}/submissions`, () => apiError(409, 'WORK_NOT_SUBMITTABLE', 'Đang chờ TL chốt deadline vòng sau.')],
      ]),
    )
    const user = userEvent.setup()
    renderApp(WORK_PAGE)
    await user.type(await screen.findByLabelText(/Link kết quả 1/), 'https://drive.google.com/file/d/1')
    await user.type(screen.getByLabelText(/Tóm tắt phần việc đã làm/), 'Bản nháp giải thích dài hơn mười ký tự')
    await user.click(screen.getByRole('button', { name: 'Gửi kết quả' }))
    expect(await screen.findByText('Đang chờ TL chốt deadline vòng sau.')).toBeInTheDocument()
    expect(screen.getByLabelText(/Link kết quả 1/)).toHaveValue('https://drive.google.com/file/d/1')
    expect(screen.getByLabelText(/Tóm tắt phần việc đã làm/)).toHaveValue('Bản nháp giải thích dài hơn mười ký tự')
  })

  it('Báo không thể hoàn thành → NEEDS_DISCUSSION (không tự chuyển cho người khác)', async () => {
    const state = { current: supportWork() }
    const api = mockApi(
      routes(GroupRole.MEMBER, state, [
        [
          'POST',
          `/support-works/${WORK_ID}/unable`,
          () => {
            state.current = supportWork({ status: SupportWorkStatus.NEEDS_DISCUSSION, allowedActions: [], version: 2 })
            return state.current
          },
        ],
      ]),
    )
    const user = userEvent.setup()
    renderApp(WORK_PAGE)
    await user.click(await screen.findByRole('button', { name: 'Báo không thể hoàn thành' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Lý do/), 'Em bị ốm')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi báo cáo' }))
    await waitFor(() => expect(screen.getByText('Cần trao đổi thêm', { selector: 'p' })).toBeInTheDocument())
    expect(api.writes('POST', `/support-works/${WORK_ID}/unable`)[0]?.body).toEqual({ reason: 'Em bị ốm', version: 1 })
    expect(screen.getByText(/Việc này không bị trừ điểm/)).toBeInTheDocument()
  })
})

describe('S10 — Requester/TL theo dõi', () => {
  it('SUBMITTED: chỉ xem, có CTA tới S11; APPROVED hiện "Đóng góp đã được công nhận" khác trạng thái chờ', async () => {
    const state = { current: submittedWork() }
    mockApi(routes(GroupRole.LEAD, state))
    renderApp(WORK_PAGE)
    expect(await screen.findAllByText('Đã gửi kết quả — chờ xác nhận')).not.toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Gửi kết quả' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem kết quả để xác nhận' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/${HELP_ID}/review`)
    expect(screen.getByText('Thời gian thực tế (tự khai): 1 giờ 35 phút')).toBeInTheDocument()
  })

  it('APPROVED: "Đóng góp đã được công nhận" + Xem ghi nhận tới S12 đúng khoản', async () => {
    const state = {
      current: supportWork({
        status: SupportWorkStatus.APPROVED,
        pointRecorded: true,
        allowedActions: [SupportWorkAction.VIEW_CONTRIBUTION],
        contribution: { id: 'c-1', workId: WORK_ID, helper: BINH, status: 'CONFIRMED', acceptedSubmissionId: 'ss-1', confirmedBy: MINH, confirmedAt: '2026-11-28T02:00:00Z', ledgerEntryId: 'led-1' },
      }),
    }
    mockApi(routes(GroupRole.MEMBER, state))
    renderApp(WORK_PAGE)
    expect(await screen.findByText('Đóng góp đã được công nhận')).toBeInTheDocument()
    expect(screen.getByText('Xác nhận bởi Minh')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem ghi nhận' })).toHaveAttribute('href', `/groups/${GROUP_ID}/points?tab=history&ledgerEntryId=led-1`)
    expect(screen.queryByText('Đã gửi kết quả — chờ xác nhận')).not.toBeInTheDocument()
  })

  it('NEEDS_DISCUSSION: TL ghi quyết định resolve-discussion với Idempotency-Key và version', async () => {
    const state = { current: supportWork({ status: SupportWorkStatus.NEEDS_DISCUSSION, allowedActions: [SupportWorkAction.RESOLVE_DISCUSSION], version: 4 }) }
    const api = mockApi(
      routes(GroupRole.LEAD, state, [['POST', `/support-works/${WORK_ID}/resolve-discussion`, () => (state.current = supportWork({ version: 5 }))]]),
    )
    const user = userEvent.setup()
    renderApp(WORK_PAGE)
    await user.type(await screen.findByLabelText(/Ghi chú cho hai bên/), 'Thống nhất làm tiếp')
    await user.click(screen.getByRole('button', { name: 'Lưu quyết định' }))
    await waitFor(() => expect(api.writes('POST', /resolve-discussion/)).toHaveLength(1))
    const call = api.writes('POST', /resolve-discussion/)[0]
    expect(call?.body).toEqual({ resolution: 'RESUME', note: 'Thống nhất làm tiếp', version: 4 })
    expect(call?.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('chưa có người nhận → trạng thái rỗng; 403 → giải thích quyền', async () => {
    mockApi([['GET', `/help-requests/${HELP_ID}`, { work: null }], ...shellRoutes(BINH, GroupRole.MEMBER)])
    renderApp(WORK_PAGE)
    expect(await screen.findByText('Yêu cầu này chưa có người nhận làm')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem yêu cầu hỗ trợ' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/${HELP_ID}`)
  })

  it('mở bằng workId (link từ thông báo): 404 ở yêu cầu hỗ trợ thì dùng chính khóa làm workId; 403 hiện giải thích quyền', async () => {
    mockApi([['GET', `/support-works/${HELP_ID}`, apiError(403, 'FORBIDDEN', 'Không có quyền')], ...shellRoutes(BINH, GroupRole.MEMBER)])
    renderApp(WORK_PAGE)
    expect(await screen.findByText('Bạn chưa có quyền xem nội dung này')).toBeInTheDocument()
  })
})
