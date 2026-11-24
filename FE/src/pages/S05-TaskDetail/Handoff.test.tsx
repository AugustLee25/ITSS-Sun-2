import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  GroupRole,
  RoundState,
  SupportWorkAction,
  SupportWorkStatus,
  TaskAction,
  TaskStatus,
  type ReviewPreviewResponse,
  type SubmitResultResponse,
} from '@/api/types'
import { AN, BINH, CYCLE3_REF, GROUP_ID, MEMBERS, MINH, cycle, round1, submission1, taskDetail, taskSummary } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes, type ApiRoute } from '@/test/renderApp'
import { HELP_ID, WORK_ID, submittedWork, supportWork } from './support/workFixtures'

afterEach(() => vi.unstubAllGlobals())

/**
 * Solution 2: không có màn "My Support Work" và không có màn nghiệm thu riêng — task nhận làm thay sống ngay trong
 * chi tiết task (S05): Helper nộp bằng popup `Gửi kết quả`, TL duyệt ở khung duyệt chung, lịch sử bàn giao nằm cùng trang.
 */
const PAGE = `/groups/${GROUP_ID}/tasks/task-1`
const takeoverPoints = {
  amount: { pointUnits: 100000, percentLabel: '10,0000' },
  status: 'AWAITING_REVIEW',
  originalAssignee: AN,
  currentRecipient: BINH,
  heldForHelpRequest: false,
  awarded: false,
} as const

/** Task của An đã giao cho Bình làm thay (cùng task, cùng vòng; người thực hiện hiện tại = Bình). */
const handedOver = (overrides: Parameters<typeof taskDetail>[0] = {}) =>
  taskDetail({
    originalAssignee: AN,
    currentExecutor: BINH,
    points: { ...takeoverPoints },
    helpRequestId: HELP_ID,
    supportWorkId: WORK_ID,
    lastSupportWorkId: WORK_ID,
    taskStatus: TaskStatus.IN_PROGRESS,
    submissions: [],
    latestSubmittedAt: null,
    timeliness: 'NOT_DUE',
    timelinessLabel: 'Chưa đến hạn',
    allowedActions: [],
    ...overrides,
  })

const shell = (role: GroupRole) =>
  [
    ['GET', `/groups/${GROUP_ID}/members`, { items: MEMBERS }],
    ['GET', `/groups/${GROUP_ID}/checkpoint-cycles`, { items: [cycle(3, { label: CYCLE3_REF.label })], page: 0, size: 30, totalItems: 1, totalPages: 1 }],
    ...shellRoutes(role === GroupRole.LEAD ? MINH : role === GroupRole.MEMBER ? BINH : AN, role),
  ] as ApiRoute[]

describe('S05 — task nhận làm thay (Solution 2)', () => {
  it('[S2-D1][S2-C8] Helper thấy nhãn `Nhận làm thay` + `Chưa tính điểm — chờ nghiệm thu` và nộp bằng popup `Gửi kết quả` của task (Idempotency-Key), không qua màn riêng', async () => {
    const submitted: SubmitResultResponse = { submission: { ...submission1, submittedBy: BINH }, task: taskSummary() }
    const api = mockApi([
      ['GET', '/tasks/task-1', handedOver({ allowedActions: [TaskAction.SUBMIT_RESULT, TaskAction.UPDATE_PROGRESS, TaskAction.REPORT_CHECKPOINT] })],
      ['GET', `/support-works/${WORK_ID}`, supportWork({ allowedActions: [SupportWorkAction.REPORT_UNABLE] })],
      ['POST', '/task-rounds/round-1/submissions', submitted],
      ...shell(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(PAGE)

    expect(await screen.findByRole('heading', { level: 1, name: 'Thiết kế API' })).toBeInTheDocument()
    const section = await screen.findByTestId('handoff-section')
    expect(within(section).getByRole('heading', { name: 'Nhận làm thay' })).toBeInTheDocument()
    expect(within(section).getByText('Bình đang làm thay An. Điểm chỉ được ghi sau khi TL nghiệm thu.')).toBeInTheDocument()
    expect(within(section).getAllByText('Chưa tính điểm — chờ nghiệm thu').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Nhận làm thay').length).toBeGreaterThan(0)

    // Không có đường dẫn nào sang màn thực hiện/nghiệm thu hỗ trợ riêng
    expect(document.querySelectorAll('a[href$="/work"], a[href$="/review"]')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Gửi kết quả' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi kết quả' })
    await user.type(within(dialog).getByLabelText(/^Đường dẫn minh chứng 1/), 'https://github.com/x/y/pull/9')
    await user.type(within(dialog).getByLabelText(/^Ghi chú kết quả/), 'Đã xong 12 endpoint và OpenAPI')
    await user.dblClick(within(dialog).getByRole('button', { name: 'Gửi kết quả' }))

    await screen.findByRole('dialog', { name: 'Kết quả đã gửi, chờ xác nhận' })
    const posts = api.writes('POST', '/task-rounds/round-1/submissions')
    expect(posts).toHaveLength(1) // bấm đúp chỉ gửi một lần
    expect(posts[0]?.headers.get('Idempotency-Key')).toBeTruthy()
    expect(posts[0]?.body).toMatchObject({ planRevisionId: 'plan-1', resultNote: 'Đã xong 12 endpoint và OpenAPI' })
    expect(api.writes('POST', /support-works/)).toHaveLength(0) // không có đường nộp riêng của S10
  })

  it('[S2-D11] người gốc xem lịch sử bàn giao ngay trong task; người không liên quan (403) không thấy khối này', async () => {
    mockApi([
      ['GET', '/tasks/task-1', handedOver({ allowedActions: [TaskAction.VIEW_RESULT] })],
      ['GET', `/support-works/${WORK_ID}`, supportWork({ allowedActions: [] })],
      ...shell(GroupRole.MEMBER).filter(([, path]) => path !== '/auth/me'),
      ['GET', '/auth/me', { userId: AN.userId, name: AN.name, email: 'an@example.com', createdAt: '2026-11-01T00:00:00Z' }],
    ])
    renderApp(PAGE)
    const section = await screen.findByTestId('handoff-section')
    expect(within(section).getByText('Người phụ trách gốc')).toBeInTheDocument()
    expect(within(section).getByText('Người hỗ trợ')).toBeInTheDocument()
    expect(within(section).getAllByText('An').length).toBeGreaterThan(0)
    expect(within(section).getAllByText('Bình').length).toBeGreaterThan(0)
    expect(within(section).getByRole('heading', { name: 'Lịch sử' })).toBeInTheDocument()
    expect(within(section).getByText('Đề nghị hỗ trợ được chấp thuận')).toBeInTheDocument()
  })

  it('[S2-D11] người không có quyền xem phần việc (403) vẫn xem được task, chỉ không thấy lịch sử bàn giao', async () => {
    mockApi([
      ['GET', '/tasks/task-1', handedOver({ allowedActions: [TaskAction.VIEW_RESULT] })],
      ['GET', `/support-works/${WORK_ID}`, apiError(403, 'FORBIDDEN', 'Không có quyền')],
      ...shell(GroupRole.MEMBER),
    ])
    renderApp(PAGE)
    expect(await screen.findByRole('heading', { level: 1, name: 'Thiết kế API' })).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('handoff-section')).not.toBeInTheDocument())
  })

  it('[S2-D8] Helper `Báo không thể hoàn thành` → cần trao đổi thêm (không tự chuyển cho người khác, không trừ điểm); TL ghi quyết định có Idempotency-Key + version', async () => {
    const state = { current: supportWork({ allowedActions: [SupportWorkAction.REPORT_UNABLE] }) }
    const api = mockApi([
      ['GET', '/tasks/task-1', handedOver({ allowedActions: [TaskAction.SUBMIT_RESULT] })],
      ['GET', `/support-works/${WORK_ID}`, () => state.current],
      [
        'POST',
        `/support-works/${WORK_ID}/unable`,
        () => {
          state.current = supportWork({ status: SupportWorkStatus.NEEDS_DISCUSSION, allowedActions: [], version: 2 })
          return state.current
        },
      ],
      ...shell(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(PAGE)
    await user.click(await screen.findByRole('button', { name: 'Báo không thể hoàn thành' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/Lý do/), 'Em bị ốm')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi báo cáo' }))
    await waitFor(() => expect(screen.getByText(/Việc này không bị trừ điểm/)).toBeInTheDocument())
    expect(api.writes('POST', `/support-works/${WORK_ID}/unable`)[0]?.body).toEqual({ reason: 'Em bị ốm', version: 1 })
  })

  it('[S2-D8] TL thống nhất xử lý sau khi Helper báo không thể: resolve-discussion có Idempotency-Key + version, ghi vào lịch sử', async () => {
    const state = { current: supportWork({ status: SupportWorkStatus.NEEDS_DISCUSSION, allowedActions: [SupportWorkAction.RESOLVE_DISCUSSION], version: 4 }) }
    const api = mockApi([
      ['GET', '/tasks/task-1', handedOver({ allowedActions: [] })],
      ['GET', `/support-works/${WORK_ID}`, () => state.current],
      ['POST', `/support-works/${WORK_ID}/resolve-discussion`, () => (state.current = supportWork({ version: 5 }))],
      ...shell(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(PAGE)
    await user.type(await screen.findByLabelText(/Ghi chú cho hai bên/), 'Thống nhất làm tiếp')
    await user.click(screen.getByRole('button', { name: 'Lưu quyết định' }))
    await waitFor(() => expect(api.writes('POST', /resolve-discussion/)).toHaveLength(1))
    const call = api.writes('POST', /resolve-discussion/)[0]
    expect(call?.body).toEqual({ resolution: 'RESUME', note: 'Thống nhất làm tiếp', version: 4 })
    expect(call?.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('[S2-D2] TL duyệt ở khung duyệt CHUNG: nhãn `Nhận làm thay`, Helper hiện tại + người gốc, minh chứng, P_t, Chấp nhận / Cần sửa / Cần cập nhật', async () => {
    const preview: ReviewPreviewResponse = {
      submission: { ...submission1, submittedBy: BINH, evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/9', label: 'PR #9' }] },
      task: taskSummary(),
      cycle: CYCLE3_REF,
      cutoffAt: '2026-11-20T15:00:00Z',
      canReview: true,
      cannotReviewReason: null,
      authorizedReviewer: MINH,
      alreadyReviewed: false,
      awardPreview: {
        recipient: BINH,
        originalAssignee: AN,
        taskTitle: 'Thiết kế API',
        points: { pointUnits: 100000, percentLabel: '+10,0000' },
        confirmedBefore: { pointUnits: 200000, percentLabel: '20,0000' },
        confirmedAfter: { pointUnits: 300000, percentLabel: '30,0000' },
        scoreLabel: '+10,0000 điểm % cho Bình từ task Thiết kế API của An',
        effectLabel: 'An không nhận điểm task này; Bình nhận toàn bộ quỹ của task',
        notice: 'Điểm chỉ được ghi sau khi kết quả được chấp nhận',
      },
      supportWork: null,
    }
    mockApi([
      [
        'GET',
        '/tasks/task-1',
        handedOver({
          taskStatus: TaskStatus.SUBMITTED,
          currentRound: round1({ state: RoundState.AWAITING_REVIEW }),
          submissions: [{ ...submission1, submittedBy: BINH, evidence: preview.submission.evidence }],
          allowedActions: [TaskAction.REVIEW_RESULT, TaskAction.VIEW_RESULT],
        }),
      ],
      ['GET', '/task-submissions/sub-1/review-preview', preview],
      ['GET', `/support-works/${WORK_ID}`, submittedWork({ allowedActions: [] })],
      ...shell(GroupRole.LEAD),
    ])
    renderApp(PAGE)

    const box = await screen.findByTestId('review-handoff')
    expect(within(box).getByText('Nhận làm thay')).toBeInTheDocument()
    expect(within(box).getByText('Nhận làm thay — nghiệm thu như task thường')).toBeInTheDocument()
    expect(within(box).getByText(/Helper: Bình · Người gốc: An/)).toBeInTheDocument()
    expect(within(box).getByText('10,0000 điểm %')).toBeInTheDocument()
    expect(within(box).getByText('Chưa tính điểm — chờ nghiệm thu')).toBeInTheDocument()
    expect(within(box).getByRole('link', { name: 'PR #9' })).toHaveAttribute('href', 'https://github.com/x/y/pull/9')
    const review = screen.getByRole('heading', { name: 'Kiểm tra kết quả' }).closest('section') as HTMLElement
    for (const label of ['Chấp nhận', 'Cần sửa', 'Cần cập nhật']) expect(await within(review).findByRole('radio', { name: label })).toBeInTheDocument()
    expect(await within(review).findByText('+10,0000 điểm % cho Bình từ task Thiết kế API của An')).toBeInTheDocument()
    // chỉ có MỘT khung duyệt: không có điều hướng sang màn xác nhận riêng
    expect(document.querySelectorAll('a[href$="/review"]')).toHaveLength(0)
  })

  it('[T-04] sau REOPEN_REQUEST: hiện "đã hoàn trả", KHÔNG còn "Cần trao đổi thêm", và có link tới yêu cầu kế nhiệm', async () => {
    mockApi([
      ['GET', '/tasks/task-1', handedOver({ currentExecutor: AN, supportWorkId: null, helpRequestId: null, allowedActions: [] })],
      [
        'GET',
        `/support-works/${WORK_ID}`,
        supportWork({ status: SupportWorkStatus.NEEDS_DISCUSSION, helpRequestStatus: 'CANCELLED', successorHelpRequestId: 'help-2', allowedActions: [] }),
      ],
      ...shell(GroupRole.LEAD),
    ])
    renderApp(PAGE)
    const returned = await screen.findByTestId('handoff-returned')
    expect(within(returned).getByText('Đã hoàn trả cho người phụ trách gốc')).toBeInTheDocument()
    expect(within(returned).getByRole('link', { name: 'Xem yêu cầu mới' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/help-2`)
    const section = screen.getByTestId('handoff-section')
    expect(within(section).queryByText('Cần trao đổi thêm')).not.toBeInTheDocument()
    expect(within(section).queryByText(/đang làm thay/)).not.toBeInTheDocument()
    expect(screen.queryByTestId('handoff-pending-points')).not.toBeInTheDocument()
  })

  it('[T-04] sau CANCEL_REQUEST (không đăng lại): hiện đã hoàn trả cho người gốc, không có link yêu cầu mới', async () => {
    mockApi([
      ['GET', '/tasks/task-1', handedOver({ currentExecutor: AN, supportWorkId: null, helpRequestId: null, allowedActions: [] })],
      ['GET', `/support-works/${WORK_ID}`, supportWork({ status: SupportWorkStatus.NEEDS_DISCUSSION, helpRequestStatus: 'CANCELLED', allowedActions: [] })],
      ...shell(GroupRole.LEAD),
    ])
    renderApp(PAGE)
    const returned = await screen.findByTestId('handoff-returned')
    expect(within(returned).getByText(/TL đã đóng yêu cầu hỗ trợ/)).toBeInTheDocument()
    expect(within(returned).queryByRole('link')).not.toBeInTheDocument()
  })

  it('[T-11] khi phần việc đã được nghiệm thu (APPROVED) không còn nói "điểm chỉ được ghi sau khi TL nghiệm thu"', async () => {
    mockApi([
      ['GET', '/tasks/task-1', handedOver({ supportWorkId: null, helpRequestId: null, allowedActions: [] })],
      ['GET', `/support-works/${WORK_ID}`, supportWork({ status: SupportWorkStatus.APPROVED, helpRequestStatus: 'COMPLETED', pointRecorded: true, allowedActions: [] })],
      ...shell(GroupRole.LEAD),
    ])
    renderApp(PAGE)
    const section = await screen.findByTestId('handoff-section')
    expect(within(section).getByText('Bình đã làm thay An và kết quả đã được TL nghiệm thu.')).toBeInTheDocument()
    expect(within(section).queryByText(/Điểm chỉ được ghi sau khi TL nghiệm thu/)).not.toBeInTheDocument()
    expect(within(section).queryByText('Chưa tính điểm — chờ nghiệm thu')).not.toBeInTheDocument()
  })
})
