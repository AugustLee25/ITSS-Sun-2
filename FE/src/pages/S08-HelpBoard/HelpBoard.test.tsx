import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type HelpRequestCard, type HelpRequestDetail, type SupportOffer } from '@/api/types'
import { AN, BINH, GROUP_ID, MINH, taskSummary } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes, type ApiRoute } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const card: HelpRequestCard = {
  id: 'hr-1',
  groupId: GROUP_ID,
  title: 'Thiết kế API (toàn bộ task)',
  descriptionPreview: 'Hoàn thiện 12 endpoint nhóm/task',
  expectedOutput: 'OpenAPI + 12 endpoint chạy được',
  task: { id: 'task-1', title: 'Thiết kế API' },
  postedBy: MINH,
  beneficiary: AN,
  deadline: '2026-11-27T13:00:00Z',
  difficulty: 'HARD',
  estimatedEffortMinutes: 360,
  points: { pointUnits: 100000, percentLabel: '10,0000' },
  pointLabel: 'Khó · +10,0000 điểm % khi TL duyệt',
  status: 'OPEN',
  statusLabel: 'Đang tìm người nhận làm thay toàn bộ task',
  myRoleInRequest: 'VIEWER',
  myOfferStatus: null,
  workId: null,
  createdAt: '2026-11-21T03:00:00Z',
}
const detail = (overrides: Partial<HelpRequestDetail> = {}): HelpRequestDetail => ({
  ...card,
  description: 'Hoàn thiện 12 endpoint nhóm/task, kèm OpenAPI',
  skillNotes: 'Spring Boot',
  supportRequestId: null,
  resultApprover: MINH,
  myOffer: null,
  offers: [],
  work: null,
  myImpact: {
    potentialBefore: { pointUnits: 200000, percentLabel: '20,0000' },
    potentialAfter: { pointUnits: 300000, percentLabel: '30,0000' },
    delta: { pointUnits: 100000, percentLabel: '+10,0000' },
  },
  pointExplanation: 'Điểm được chuyển toàn bộ từ quỹ của task; chỉ ghi sau khi TL duyệt kết quả.',
  allowedActions: ['OFFER_HELP'],
  version: 2,
  ...overrides,
})
const page = <T,>(items: T[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 })
const offer: SupportOffer = {
  id: 'off-1',
  helpRequestId: 'hr-1',
  helper: BINH,
  message: 'Mình rảnh tối nay',
  status: 'PENDING',
  needsReconfirmation: false,
  createdAt: '2026-11-21T04:00:00Z',
  decidedAt: null,
  decidedBy: null,
}
const setup = (role: GroupRole, routes: ApiRoute[]) => mockApi([...routes, ...shellRoutes(role === GroupRole.LEAD ? MINH : BINH, role)])

describe('S08 — Bảng hỗ trợ', () => {
  it('Member: thẻ hiện điểm do server trả, không có nút đăng; gửi đề nghị riêng qua TL', async () => {
    const api = setup(GroupRole.MEMBER, [['GET', `/groups/${GROUP_ID}/help-requests`, page([card])]])
    renderApp(`/groups/${GROUP_ID}/help`)

    expect(await screen.findByRole('link', { name: 'Thiết kế API (toàn bộ task)' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/hr-1`)
    expect(screen.getByText('+10,0000 điểm % khi TL duyệt')).toBeInTheDocument()
    expect(screen.getByText('Đang tìm người nhận làm thay toàn bộ task')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Đăng yêu cầu' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Gửi đề nghị cho TL' })).toBeInTheDocument()
    expect(api.calls.find((c) => c.path.endsWith('/help-requests'))?.search.get('tab')).toBe('OPEN')

    await userEvent.setup().click(screen.getByRole('tab', { name: 'Tôi đã đề nghị' }))
    await waitFor(() => expect(api.calls.some((c) => c.search.get('tab') === 'MY_OFFERS')).toBe(true))
  })

  it('TL: đăng yêu cầu cho toàn bộ task — xem trước P_t, beneficiary là người phụ trách, có Idempotency-Key', async () => {
    const api = setup(GroupRole.LEAD, [
      ['GET', `/groups/${GROUP_ID}/help-requests`, page([])],
      ['GET', `/groups/${GROUP_ID}/tasks`, page([taskSummary({ taskStatus: 'IN_PROGRESS' })])],
      ['POST', `/groups/${GROUP_ID}/help-requests`, detail({ allowedActions: ['CANCEL'] })],
      ['GET', '/help-requests/hr-1', detail({ allowedActions: ['CANCEL'] })],
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/help`)

    expect(await screen.findByText('Chưa có phần việc hỗ trợ nào đang mở')).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Đăng yêu cầu' })[0])
    const dialog = await screen.findByRole('dialog', { name: 'Đăng yêu cầu hỗ trợ' })
    await user.selectOptions(await within(dialog).findByLabelText(/Task/), 'task-1')
    expect(within(dialog).getByText('Chuyển toàn bộ 10,0000 điểm % của task Thiết kế API từ phần của An cho người được chọn.')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Đăng yêu cầu' }))
    expect(await within(dialog).findByText('Tên từ 3 đến 120 ký tự')).toBeInTheDocument()
    expect(api.writes('POST', `/groups/${GROUP_ID}/help-requests`)).toHaveLength(0)

    await user.type(within(dialog).getByLabelText(/Tên phần việc/), 'Thiết kế API (toàn bộ task)')
    await user.type(within(dialog).getByLabelText(/Phạm vi và tiêu chí/), 'Hoàn thiện 12 endpoint nhóm/task')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Đăng yêu cầu' }))

    await waitFor(() => expect(api.writes('POST', `/groups/${GROUP_ID}/help-requests`)).toHaveLength(1))
    const call = api.writes('POST', `/groups/${GROUP_ID}/help-requests`)[0]
    expect(call.headers.get('Idempotency-Key')).toBeTruthy()
    expect(call.body).toMatchObject({ taskId: 'task-1', beneficiaryId: 'u-an', privacyChecked: true, supportRequestId: null })
    expect(call.body).not.toHaveProperty('points')
    expect(await screen.findByRole('heading', { name: 'Thiết kế API (toàn bộ task)' })).toBeInTheDocument()
  })
})

describe('S09 — Chi tiết yêu cầu hỗ trợ', () => {
  it('Member: đề nghị tự nguyện — phải xác nhận đã xem phạm vi; gửi kèm version; điểm chỉ "khi TL duyệt"', async () => {
    const api = setup(GroupRole.MEMBER, [
      ['GET', '/help-requests/hr-1', detail()],
      ['POST', '/help-requests/hr-1/offers', offer],
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/help/hr-1`)

    expect(await screen.findByRole('heading', { name: 'Thiết kế API (toàn bộ task)' })).toBeInTheDocument()
    expect(screen.getByText('+10,0000 điểm % khi TL duyệt')).toBeInTheDocument()
    expect(screen.getByText(/Có thể đạt 20,0000% → 30,0000%/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem ảnh hưởng đến điểm của tôi' })).toHaveAttribute('href', `/groups/${GROUP_ID}/points`)
    expect(screen.queryByRole('button', { name: 'Chấp thuận' })).not.toBeInTheDocument()
    expect(screen.queryByText(/đã cộng/i)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Mình có thể hỗ trợ' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề nghị' }))
    expect(within(dialog).getByText('Hãy xác nhận bạn đã xem phạm vi trước khi gửi')).toBeInTheDocument()
    expect(api.writes('POST', '/help-requests/hr-1/offers')).toHaveLength(0)

    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề nghị' }))
    await waitFor(() => expect(api.writes('POST', '/help-requests/hr-1/offers')).toHaveLength(1))
    expect(api.writes('POST', '/help-requests/hr-1/offers')[0].body).toEqual({ message: null, acknowledgedScope: true, helpRequestVersion: 2 })
  })

  it('Member đã đề nghị: hiện trạng thái chờ và rút đề nghị không cần lý do', async () => {
    const api = setup(GroupRole.MEMBER, [
      ['GET', '/help-requests/hr-1', detail({ allowedActions: ['WITHDRAW_OFFER'], myOffer: offer, myOfferStatus: 'PENDING' })],
      ['POST', '/support-offers/off-1/withdraw', { ...offer, status: 'WITHDRAWN' }],
    ])
    renderApp(`/groups/${GROUP_ID}/help/hr-1`)
    expect(await screen.findByText('Đã gửi đề nghị — chờ người đăng phản hồi.')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Rút đề nghị' }))
    await waitFor(() => expect(api.writes('POST', '/support-offers/off-1/withdraw')).toHaveLength(1))
    expect(api.writes('POST', '/support-offers/off-1/withdraw')[0].body).toBeUndefined()
  })

  it('TL chọn Helper: xác nhận phạm vi rồi gọi accept-offer (Idempotency-Key + version)', async () => {
    const api = setup(GroupRole.LEAD, [
      ['GET', '/help-requests/hr-1', detail({ allowedActions: ['ACCEPT_OFFER', 'CANCEL'], offers: [offer], myRoleInRequest: 'REQUESTER' })],
      ['POST', '/help-requests/hr-1/accept-offer', detail({ status: 'IN_PROGRESS', allowedActions: ['OPEN_WORK'] })],
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/help/hr-1`)

    expect(await screen.findByText('Bình')).toBeInTheDocument()
    expect(screen.getByText('Mình rảnh tối nay')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Chấp thuận' }))
    const confirm = await screen.findByRole('alertdialog')
    await user.click(within(confirm).getByRole('button', { name: 'Chấp thuận' }))

    await waitFor(() => expect(api.writes('POST', '/help-requests/hr-1/accept-offer')).toHaveLength(1))
    const call = api.writes('POST', '/help-requests/hr-1/accept-offer')[0]
    expect(call.headers.get('Idempotency-Key')).toBeTruthy()
    expect(call.body).toEqual({ offerId: 'off-1', version: 2 })
  })

  it('người nhận sau khi yêu cầu đã có người: thông báo "Yêu cầu đã có người hỗ trợ" và nút quay lại danh sách', async () => {
    setup(GroupRole.MEMBER, [
      ['GET', '/help-requests/hr-1', detail()],
      ['POST', '/help-requests/hr-1/offers', apiError(409, 'HELP_ALREADY_MATCHED', 'x')],
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/help/hr-1`)
    await user.click(await screen.findByRole('button', { name: 'Mình có thể hỗ trợ' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox'))
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề nghị' }))
    expect(await within(dialog).findByText('Yêu cầu đã có người hỗ trợ')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'Về bảng hỗ trợ' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help`)
  })
})
