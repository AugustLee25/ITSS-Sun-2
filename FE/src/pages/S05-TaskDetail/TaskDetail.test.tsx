import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  GroupRole,
  PlanStatus,
  RoundState,
  TaskAction,
  TaskStatus,
  type ReviewPreviewResponse,
  type ReviewResultResponse,
  type SubmitResultResponse,
} from '@/api/types'
import { AN, CYCLE3_REF, GROUP_ID, MEMBERS, MINH, cycle, effectivePlan, round1, submission1, taskDetail, taskSummary } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes } from '@/test/renderApp'
import { cheerIndex, isHttpsUrl } from './evidence'

afterEach(() => vi.unstubAllGlobals())

const TASK = `/tasks/task-1`
const cycles = {
  items: [cycle(5), cycle(4, { label: 'Kỳ 4 · 20/11 22:00 → 27/11 22:00' }), cycle(3, { label: CYCLE3_REF.label })],
  page: 0,
  size: 30,
  totalItems: 3,
  totalPages: 1,
}
const common = (role: GroupRole) =>
  [
    ['GET', `/groups/${GROUP_ID}/members`, { items: MEMBERS }],
    ['GET', `/groups/${GROUP_ID}/checkpoint-cycles`, cycles],
    ...shellRoutes(role === GroupRole.LEAD ? MINH : AN, role),
  ] as Parameters<typeof mockApi>[0]

const pageUrl = `/groups/${GROUP_ID}/tasks/task-1`

describe('helper S05', () => {
  it('isHttpsUrl chỉ nhận https hợp lệ ≤ 2048 ký tự; cheerIndex ổn định và nằm trong khoảng', () => {
    expect(isHttpsUrl('https://github.com/x/y/pull/7')).toBe(true)
    expect(isHttpsUrl('http://github.com/x')).toBe(false)
    expect(isHttpsUrl('github.com/x')).toBe(false)
    expect(isHttpsUrl(`https://a.com/${'x'.repeat(2100)}`)).toBe(false)
    const index = cheerIndex('task-1', 1, 4)
    expect(index).toBeGreaterThanOrEqual(0)
    expect(index).toBeLessThan(4)
    expect(cheerIndex('task-1', 1, 4)).toBe(index)
  })
})

describe('S05 — xem chi tiết (Member)', () => {
  it('hai nhãn kế hoạch/thực hiện tách bạch, mốc thời gian, kết quả đã nộp (đúng hạn, bản TL sẽ xét), điểm P_t và quyền riêng tư', async () => {
    mockApi([
      ['GET', TASK, taskDetail({ originalDeadline: '2026-11-19T13:00:00Z', allowedActions: [TaskAction.VIEW_RESULT, TaskAction.REPORT_CHECKPOINT, TaskAction.REPORT_DIFFICULTY] })],
      ...common(GroupRole.MEMBER),
    ])
    renderApp(pageUrl)

    expect(await screen.findByRole('heading', { level: 1, name: 'Thiết kế API' })).toBeInTheDocument()
    expect(screen.getByText('Kế hoạch: Đã duyệt')).toBeInTheDocument()
    expect(screen.getAllByText('Thực hiện: Đã nộp — chờ TL duyệt').length).toBeGreaterThan(0)

    const info = screen.getByRole('heading', { name: 'Thông tin task' }).closest('section') as HTMLElement
    expect(within(info).getByText('Deadline vòng hiện tại')).toBeInTheDocument()
    expect(within(info).getByText('Deadline gốc')).toBeInTheDocument()
    expect(within(info).getByText('Thời gian nộp')).toBeInTheDocument()
    expect(within(info).getByText('Nộp đúng hạn — chờ TL duyệt')).toBeInTheDocument()
    expect(within(info).getByText(/Đề nghị hỗ trợ riêng chỉ người gửi và trưởng nhóm đọc được/)).toBeInTheDocument()

    const results = screen.getByRole('heading', { name: 'Kết quả đã nộp' }).closest('section') as HTMLElement
    expect(within(results).getByText('Nộp đúng hạn')).toBeInTheDocument()
    expect(within(results).getByText('Bản TL sẽ xét')).toBeInTheDocument()
    const link = within(results).getByRole('link', { name: 'PR #7' })
    expect(link).toHaveAttribute('href', 'https://github.com/x/y/pull/7')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))

    const points = screen.getByRole('heading', { name: 'Điểm của task' }).closest('section') as HTMLElement
    expect(within(points).getByText('10,0000 điểm %')).toBeInTheDocument()
    expect(within(points).getByText('Điểm chỉ được ghi sau khi TL chấp nhận kết quả.')).toBeInTheDocument()

    // CTA theo allowedActions: không có "Gửi kết quả"/duyệt
    expect(screen.queryByRole('button', { name: 'Gửi kết quả' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận quyết định' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Báo cáo checkpoint' })).toHaveAttribute('href', `${pageUrl}/checkpoint`)
    expect(screen.getByRole('link', { name: 'Báo khó khăn' })).toHaveAttribute('href', `${pageUrl}/difficulty`)
  })

  it('bản sửa chờ duyệt hiển thị song song với bản đang hiệu lực, kèm giá trị cũ/mới, người gửi, lý do', async () => {
    const pending = effectivePlan({
      id: 'plan-2',
      revisionNo: 2,
      status: PlanStatus.PENDING_APPROVAL,
      isEffective: false,
      baseRevisionId: 'plan-1',
      changeReason: 'Cần thêm 1 ngày do phụ thuộc dữ liệu',
      deadline: '2026-11-21T13:00:00Z',
      reviewedBy: null,
      reviewedAt: null,
      changes: [
        {
          field: 'deadline',
          oldValue: '2026-11-20T13:00:00Z',
          newValue: '2026-11-21T13:00:00Z',
          oldLabel: '20:00 Thứ Sáu 20/11',
          newLabel: '20:00 Thứ Bảy 21/11',
        },
      ],
      version: 4,
    })
    mockApi([
      ['GET', TASK, taskDetail({ pendingRevision: pending, pendingRevisionId: 'plan-2', pendingRevisionStatus: PlanStatus.PENDING_APPROVAL })],
      ...common(GroupRole.MEMBER),
    ])
    renderApp(pageUrl)
    expect(await screen.findByRole('heading', { name: 'Kế hoạch đang có hiệu lực' })).toBeInTheDocument()
    const section = screen.getByRole('heading', { name: 'Bản sửa chờ duyệt' }).closest('section') as HTMLElement
    expect(within(section).getByText('20:00 Thứ Sáu 20/11')).toBeInTheDocument()
    expect(within(section).getByText('20:00 Thứ Bảy 21/11')).toBeInTheDocument()
    expect(within(section).getByText(/Cần thêm 1 ngày do phụ thuộc dữ liệu/)).toBeInTheDocument()
    expect(within(section).getByText(/Bản đang có hiệu lực vẫn giữ nguyên/)).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Duyệt kế hoạch' })).not.toBeInTheDocument()
  })

  it('task không còn: về danh sách S04 kèm thông báo', async () => {
    mockApi([['GET', TASK, apiError(404, 'NOT_FOUND', 'Không có task')], ['GET', `/groups/${GROUP_ID}/tasks`, { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }], ...common(GroupRole.MEMBER)])
    renderApp(pageUrl)
    expect(await screen.findByText('Task này không còn tồn tại hoặc bạn không có quyền xem.')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 1, name: 'Công việc' })).toBeInTheDocument()
  })

  it('lịch sử task tải khi mở, hiển thị câu do server dựng', async () => {
    mockApi([
      ['GET', TASK, taskDetail()],
      [
        'GET',
        `${TASK}/history`,
        {
          items: [
            { id: 'h1', at: '2026-11-20T12:30:00Z', actor: AN, kind: 'RESULT_SUBMITTED', roundNo: 1, summary: 'An nộp kết quả lần 1 (đúng hạn)', refId: 'sub-1' },
          ],
          page: 0,
          size: 20,
          totalItems: 1,
          totalPages: 1,
        },
      ],
      ...common(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Xem lịch sử' }))
    expect(await screen.findByText('An nộp kết quả lần 1 (đúng hạn)')).toBeInTheDocument()
  })
})

describe('S05 — Member soạn kế hoạch', () => {
  const draft = effectivePlan({ id: 'plan-d', status: PlanStatus.DRAFT, isEffective: false, version: 2, submittedBy: null, submittedAt: null, reviewedBy: null, reviewedAt: null })
  const draftTask = taskDetail({
    effectivePlan: null,
    pendingRevision: draft,
    pendingRevisionId: 'plan-d',
    pendingRevisionStatus: PlanStatus.DRAFT,
    committed: false,
    planStatus: PlanStatus.DRAFT,
    taskStatus: TaskStatus.TODO,
    submissions: [],
    currentRound: round1({ state: RoundState.DRAFT, effectivePlanId: null }),
    rounds: [round1({ state: RoundState.DRAFT, effectivePlanId: null })],
    allowedActions: [TaskAction.EDIT_PLAN, TaskAction.SUBMIT_PLAN],
  })

  it('Lưu nháp chỉ gửi trường đã đổi kèm version; Gửi TL duyệt lưu rồi gửi với version mới nhất, có Idempotency-Key', async () => {
    const api = mockApi([
      ['GET', TASK, draftTask],
      ['PATCH', '/plans/plan-d', { plan: { ...draft, title: 'Thiết kế API v2', version: 3 }, task: taskSummary() }],
      ['POST', '/plans/plan-d/submit', { plan: { ...draft, status: PlanStatus.PENDING_APPROVAL, version: 4 }, task: taskSummary() }],
      ...common(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)

    await user.click(await screen.findByRole('button', { name: 'Sửa kế hoạch' }))
    const title = await screen.findByLabelText(/^Tên task/)
    expect(title).toHaveValue('Thiết kế API')
    expect(screen.getByLabelText(/^Ngày deadline/)).toHaveValue('2026-11-20')
    expect(screen.getByLabelText(/^Giờ deadline/)).toHaveValue('20:00')

    await user.clear(title)
    await user.type(title, 'Thiết kế API v2')
    await user.click(screen.getByRole('button', { name: 'Lưu nháp' }))
    await waitFor(() => expect(api.writes('PATCH', '/plans/plan-d')).toHaveLength(1))
    expect(api.writes('PATCH', '/plans/plan-d')[0]?.body).toEqual({ title: 'Thiết kế API v2', version: 2 })

    // mở lại editor, sửa tiếp rồi gửi: PATCH (version 2 vì dữ liệu server mock không đổi) → POST submit với version của phản hồi PATCH
    await user.click(await screen.findByRole('button', { name: 'Sửa kế hoạch' }))
    const title2 = await screen.findByLabelText(/^Tên task/)
    await user.clear(title2)
    await user.type(title2, 'Thiết kế API v3')
    await user.click(screen.getByRole('button', { name: 'Gửi TL duyệt' }))
    await waitFor(() => expect(api.writes('POST', '/plans/plan-d/submit')).toHaveLength(1))
    const submit = api.writes('POST', '/plans/plan-d/submit')[0]!
    expect(submit.body).toEqual({ version: 3 })
    expect(submit.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('thiếu trường bắt buộc: báo lỗi ngay tại trường, không gọi API', async () => {
    const api = mockApi([['GET', TASK, draftTask], ...common(GroupRole.MEMBER)])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Sửa kế hoạch' }))
    await user.clear(await screen.findByLabelText(/^Phạm vi công việc/))
    await user.click(screen.getByRole('button', { name: 'Gửi TL duyệt' }))
    expect(await screen.findByText(/Hãy mô tả phạm vi công việc/)).toBeInTheDocument()
    expect(api.calls.filter((c) => c.method !== 'GET')).toHaveLength(0)
  })
})

describe('S05 — TL duyệt kế hoạch', () => {
  const pending = effectivePlan({ id: 'plan-2', revisionNo: 2, status: PlanStatus.PENDING_APPROVAL, isEffective: false, version: 4, reviewedBy: null, reviewedAt: null })
  const detail = taskDetail({
    pendingRevision: pending,
    pendingRevisionStatus: PlanStatus.PENDING_APPROVAL,
    allowedActions: [TaskAction.REVIEW_PLAN],
  })

  it('Duyệt kế hoạch gửi quyết định + version + Idempotency-Key; Yêu cầu chỉnh sửa bắt buộc lý do ≥ 5 ký tự', async () => {
    const api = mockApi([
      ['GET', TASK, detail],
      ['POST', '/plans/plan-2/review', { plan: pending, task: taskSummary() }],
      ...common(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)

    await user.click(await screen.findByRole('button', { name: 'Yêu cầu chỉnh sửa' }))
    const dialog = await screen.findByRole('dialog', { name: 'Yêu cầu chỉnh sửa kế hoạch' })
    await user.type(within(dialog).getByLabelText(/^Lý do trả lại/), 'ôi')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))
    expect(await within(dialog).findByText('Hãy nêu lý do (5–1000 ký tự)')).toBeInTheDocument()
    expect(api.writes('POST', '/plans/plan-2/review')).toHaveLength(0)
    await user.type(within(dialog).getByLabelText(/^Lý do trả lại/), ' cần rõ phạm vi')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))
    await waitFor(() => expect(api.writes('POST', '/plans/plan-2/review')).toHaveLength(1))
    expect(api.writes('POST', '/plans/plan-2/review')[0]?.body).toEqual({ decision: 'CHANGES_REQUESTED', note: 'ôi cần rõ phạm vi', version: 4 })

    await user.click(await screen.findByRole('button', { name: 'Duyệt kế hoạch' }))
    await waitFor(() => expect(api.writes('POST', '/plans/plan-2/review')).toHaveLength(2))
    const approve = api.writes('POST', '/plans/plan-2/review')[1]!
    expect(approve.body).toEqual({ decision: 'APPROVED', version: 4 })
    expect(approve.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('xung đột phiên bản (409) hiện hướng dẫn tải bản mới thay vì mất dữ liệu', async () => {
    mockApi([
      ['GET', TASK, detail],
      ['POST', '/plans/plan-2/review', apiError(409, 'VERSION_CONFLICT', 'Phiên bản cũ')],
      ...common(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Duyệt kế hoạch' }))
    expect(await screen.findByText(/Task vừa được cập nhật ở nơi khác/)).toBeInTheDocument()
  })
})

describe('S05 — Member gửi kết quả', () => {
  const executing = taskDetail({
    taskStatus: TaskStatus.IN_PROGRESS,
    submissions: [],
    latestSubmittedAt: null,
    timeliness: 'NOT_DUE',
    allowedActions: [TaskAction.SUBMIT_RESULT, TaskAction.UPDATE_PROGRESS, TaskAction.REPORT_CHECKPOINT],
  })
  const submitted: SubmitResultResponse = { submission: submission1, task: taskSummary() }

  it('chặn URL không hợp lệ ngay tại ô nhập; nộp thành công gửi Idempotency-Key, hiện lời động viên (không nói "đã cộng điểm")', async () => {
    const api = mockApi([
      ['GET', TASK, executing],
      ['POST', '/task-rounds/round-1/submissions', submitted],
      ...common(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)

    await user.click(await screen.findByRole('button', { name: 'Gửi kết quả' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi kết quả' })
    await user.type(within(dialog).getByLabelText(/^Đường dẫn minh chứng 1/), 'http://khong-bao-mat.example')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi kết quả' }))
    expect(await within(dialog).findByText(/Hãy nhập đường dẫn bắt đầu bằng https/)).toBeInTheDocument()
    expect(api.writes('POST', '/task-rounds/round-1/submissions')).toHaveLength(0)

    const url = within(dialog).getByLabelText(/^Đường dẫn minh chứng 1/)
    await user.clear(url)
    await user.type(url, 'https://github.com/x/y/pull/7')
    await user.type(within(dialog).getByLabelText(/^Tên hiển thị 1/), 'PR #7')
    await user.type(within(dialog).getByLabelText(/^Ghi chú kết quả/), 'Đã xong 12 endpoint')
    await user.dblClick(within(dialog).getByRole('button', { name: 'Gửi kết quả' }))

    const cheer = await screen.findByRole('dialog', { name: 'Kết quả đã gửi, chờ xác nhận' })
    expect(within(cheer).getByText(/Bản nộp đúng hạn/)).toBeInTheDocument()
    expect(within(cheer).getByText('Điểm chỉ được ghi sau khi TL chấp nhận kết quả.')).toBeInTheDocument()
    expect(within(cheer).queryByText(/đã cộng điểm|hoàn thành/i)).not.toBeInTheDocument()
    expect(within(cheer).getByText(/(Làm tốt lắm|Cảm ơn bạn|Một bước nhỏ|Cố lên)/)).toBeInTheDocument()

    const posts = api.writes('POST', '/task-rounds/round-1/submissions')
    expect(posts).toHaveLength(1)
    expect(posts[0]?.body).toEqual({
      planRevisionId: 'plan-1',
      resultNote: 'Đã xong 12 endpoint',
      evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/7', label: 'PR #7' }],
    })
    expect(posts[0]?.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('quá giờ chốt (409 SUBMISSION_WINDOW_CLOSED): giữ nguyên nội dung đã nhập và hiện thông điệp server', async () => {
    mockApi([
      ['GET', TASK, executing],
      ['POST', '/task-rounds/round-1/submissions', apiError(409, 'SUBMISSION_WINDOW_CLOSED', 'Đã quá giờ chốt của kỳ; chờ TL chốt vòng sau.')],
      ...common(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Gửi kết quả' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi kết quả' })
    await user.type(within(dialog).getByLabelText(/^Đường dẫn minh chứng 1/), 'https://drive.example/doc')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi kết quả' }))
    expect(await within(dialog).findByText('Đã quá giờ chốt của kỳ; chờ TL chốt vòng sau.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/^Đường dẫn minh chứng 1/)).toHaveValue('https://drive.example/doc')
  })

  it('cập nhật tiến độ gửi ghi chú + tình trạng, không có trường deadline', async () => {
    const api = mockApi([
      ['GET', TASK, executing],
      ['POST', `${TASK}/progress`, { id: 'p1', taskId: 'task-1', by: AN, note: 'x', riskStatus: 'AT_RISK', expectedCompletionAt: null, createdAt: '2026-11-18T03:00:00Z' }],
      ...common(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Cập nhật tiến độ' }))
    const dialog = await screen.findByRole('dialog', { name: 'Cập nhật tiến độ' })
    await user.type(within(dialog).getByLabelText(/^Nội dung cập nhật/), 'Đang chờ dữ liệu mẫu')
    await user.selectOptions(within(dialog).getByLabelText('Tình trạng công việc'), 'AT_RISK')
    await user.click(within(dialog).getByRole('button', { name: 'Lưu cập nhật' }))
    await waitFor(() => expect(api.writes('POST', `${TASK}/progress`)).toHaveLength(1))
    expect(api.writes('POST', `${TASK}/progress`)[0]?.body).toEqual({ note: 'Đang chờ dữ liệu mẫu', riskStatus: 'AT_RISK' })
  })
})

describe('S05 — TL duyệt kết quả', () => {
  const lead = taskDetail({ allowedActions: [TaskAction.REVIEW_RESULT, TaskAction.VIEW_RESULT] })
  const preview = (overrides: Partial<ReviewPreviewResponse> = {}): ReviewPreviewResponse => ({
    submission: submission1,
    task: taskSummary(),
    cycle: CYCLE3_REF,
    cutoffAt: '2026-11-20T15:00:00Z',
    canReview: true,
    cannotReviewReason: null,
    authorizedReviewer: MINH,
    alreadyReviewed: false,
    awardPreview: {
      recipient: AN,
      originalAssignee: AN,
      taskTitle: 'Thiết kế API',
      points: { pointUnits: 100000, percentLabel: '+10,0000' },
      confirmedBefore: { pointUnits: 200000, percentLabel: '20,0000' },
      confirmedAfter: { pointUnits: 300000, percentLabel: '30,0000' },
      scoreLabel: '+10,0000 điểm % cho An từ task Thiết kế API của An',
      effectLabel: 'An nhận toàn bộ quỹ của task',
      notice: 'Điểm chỉ được ghi sau khi kết quả được chấp nhận',
    },
    supportWork: null,
    ...overrides,
  })
  const reviewResponse = (decision: 'ACCEPTED' | 'FIX_REQUIRED'): ReviewResultResponse => ({
    review: { id: 'rv1', submissionId: 'sub-1', decision, reviewer: MINH, issueDescription: null, reviewedAt: '2026-11-21T02:00:00Z' },
    submission: submission1,
    task: taskSummary(),
    award:
      decision === 'ACCEPTED'
        ? {
            ledgerEntryId: 'l1',
            settlementId: 's1',
            recipient: AN,
            taskId: 'task-1',
            points: { pointUnits: 100000, percentLabel: '+10,0000' },
            confirmedBefore: { pointUnits: 200000, percentLabel: '20,0000' },
            confirmedAfter: { pointUnits: 300000, percentLabel: '30,0000' },
            source: 'OWN',
          }
        : null,
    supportWork: null,
  })

  it('chưa tới giờ chốt: hiện lý do do server trả và không cho duyệt', async () => {
    mockApi([
      ['GET', TASK, lead],
      [
        'GET',
        '/task-submissions/sub-1/review-preview',
        preview({ canReview: false, cannotReviewReason: { code: 'REVIEW_BEFORE_CUTOFF', message: 'Chưa đến giờ chốt của kỳ — TL duyệt sau 22:00 Thứ Sáu 20/11.' } }),
      ],
      ...common(GroupRole.LEAD),
    ])
    renderApp(pageUrl)
    expect(await screen.findByText('Chưa đến giờ chốt của kỳ — TL duyệt sau 22:00 Thứ Sáu 20/11.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xác nhận quyết định' })).not.toBeInTheDocument()
  })

  it('Cần sửa bắt buộc mô tả ≥ 10 ký tự; Chấp nhận ghi điểm: thông báo "Đã ghi nhận +10,0000 điểm % từ task …" và gửi Idempotency-Key', async () => {
    const api = mockApi([
      ['GET', TASK, lead],
      ['GET', '/task-submissions/sub-1/review-preview', preview()],
      ['POST', '/task-submissions/sub-1/review', (call) => reviewResponse((call.body as { decision: 'ACCEPTED' | 'FIX_REQUIRED' }).decision)],
      ...common(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)

    expect(await screen.findByText('+10,0000 điểm % cho An từ task Thiết kế API của An')).toBeInTheDocument()
    expect(screen.getByText('Điểm chỉ được ghi sau khi kết quả được chấp nhận')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Cần sửa' }))
    await user.click(screen.getByRole('button', { name: 'Xác nhận quyết định' }))
    expect(await screen.findByText('Hãy mô tả rõ (10–2000 ký tự)')).toBeInTheDocument()
    expect(api.writes('POST', '/task-submissions/sub-1/review')).toHaveLength(0)

    await user.type(screen.getByLabelText(/^Mô tả lỗi hoặc yêu cầu cập nhật/), 'Thiếu 3 endpoint nhóm')
    await user.click(screen.getByRole('button', { name: 'Xác nhận quyết định' }))
    await waitFor(() => expect(api.writes('POST', '/task-submissions/sub-1/review')).toHaveLength(1))
    expect(api.writes('POST', '/task-submissions/sub-1/review')[0]?.body).toEqual({ decision: 'FIX_REQUIRED', issueDescription: 'Thiếu 3 endpoint nhóm' })
    expect(await screen.findByText(/Đã ghi quyết định "Cần sửa"/)).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: 'Chấp nhận' }))
    await user.click(screen.getByRole('button', { name: 'Xác nhận quyết định' }))
    await waitFor(() => expect(api.writes('POST', '/task-submissions/sub-1/review')).toHaveLength(2))
    const accept = api.writes('POST', '/task-submissions/sub-1/review')[1]!
    expect(accept.body).toEqual({ decision: 'ACCEPTED', issueDescription: null })
    expect(accept.headers.get('Idempotency-Key')).toBeTruthy()
    expect(await screen.findByText('Đã ghi nhận +10,0000 điểm % từ task Thiết kế API')).toBeInTheDocument()
  })

  it('bản nộp đã cũ (409 STALE_SUBMISSION): hiện hướng dẫn tải lại', async () => {
    mockApi([
      ['GET', TASK, lead],
      ['GET', '/task-submissions/sub-1/review-preview', preview()],
      ['POST', '/task-submissions/sub-1/review', apiError(409, 'STALE_SUBMISSION', 'Có bản nộp mới hơn')],
      ...common(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Xác nhận quyết định' }))
    expect(await screen.findByText(/Có bản nộp mới hơn hoặc quyền nhận điểm vừa đổi/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tải lại' })).toBeInTheDocument()
  })
})

describe('S05 — TL chốt deadline xử lý tiếp', () => {
  const needsFix = taskDetail({
    taskStatus: TaskStatus.FIX_REQUIRED,
    needsCarryover: true,
    carryoverReason: 'FIX_REQUIRED',
    timeliness: 'NEEDS_REWORK',
    timelinessLabel: 'Cần sửa/cập nhật',
    isLate: true,
    currentRound: round1({ state: RoundState.CARRYOVER_REQUIRED, decision: 'FIX_REQUIRED' }),
    allowedActions: [TaskAction.SET_CARRYOVER, TaskAction.VIEW_RESULT],
  })

  it('chọn kỳ sau + deadline (giờ nhóm → UTC), kỳ không liền sau bắt buộc lý do, có Idempotency-Key', async () => {
    const api = mockApi([
      ['GET', TASK, needsFix],
      ['POST', '/task-rounds/round-1/carryover', { sourceRound: round1(), targetRound: round1(), plan: effectivePlan(), task: needsFix }],
      ...common(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(pageUrl)
    await user.click(await screen.findByRole('button', { name: 'Chốt deadline xử lý tiếp' }))
    const dialog = await screen.findByRole('dialog', { name: 'Chốt deadline xử lý tiếp' })

    await user.selectOptions(within(dialog).getByLabelText(/^Kỳ xử lý tiếp/), 'cyc-5')
    fireEvent.change(within(dialog).getByLabelText(/^Ngày deadline mới/), { target: { value: '2026-12-04' } })
    await user.click(within(dialog).getByRole('button', { name: 'Chốt deadline' }))
    expect(await within(dialog).findByText('Hãy nêu lý do chọn kỳ này (5–1000 ký tự)')).toBeInTheDocument()
    expect(api.writes('POST', '/task-rounds/round-1/carryover')).toHaveLength(0)

    await user.selectOptions(within(dialog).getByLabelText(/^Kỳ xử lý tiếp/), 'cyc-4')
    fireEvent.change(within(dialog).getByLabelText(/^Ngày deadline mới/), { target: { value: '2026-11-27' } })
    await user.click(within(dialog).getByRole('button', { name: 'Chốt deadline' }))
    await waitFor(() => expect(api.writes('POST', '/task-rounds/round-1/carryover')).toHaveLength(1))
    const call = api.writes('POST', '/task-rounds/round-1/carryover')[0]!
    expect(call.body).toEqual({ targetCycleId: 'cyc-4', deadline: '2026-11-27T13:00:00.000Z', scopeNote: null, decisionReason: null })
    expect(call.headers.get('Idempotency-Key')).toBeTruthy()
  })
})
