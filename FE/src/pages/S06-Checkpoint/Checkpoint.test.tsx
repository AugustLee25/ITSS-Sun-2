import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type MyReportResponse, type ReportItem } from '@/api/types'
import { AN, GROUP_ID, cycle } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes, type ApiRoute } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const PATH = `/groups/${GROUP_ID}/checkpoints/cyc-3`
const API = `/groups/${GROUP_ID}/checkpoint-cycles/cyc-3/my-report`

const submittedItem: ReportItem = {
  taskId: 'task-1',
  roundId: 'round-1',
  roundNo: 1,
  title: 'Thiết kế API',
  expectedOutput: 'OpenAPI + 12 endpoint chạy được',
  deadline: '2026-11-20T13:00:00Z',
  difficulty: 'HARD',
  taskStatus: 'SUBMITTED',
  timeliness: 'ON_TIME_PENDING_REVIEW',
  timelinessLabel: 'Nộp đúng hạn — chờ TL duyệt',
  isLate: false,
  submission: {
    id: 'sub-1',
    submissionNo: 1,
    submittedAt: '2026-11-18T02:30:00Z',
    isOnTime: true,
    evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/7', label: 'PR #7' }],
    reviewDecision: null,
  },
  declaredResult: 'RESULT_SUBMITTED',
  prefilled: true,
  riskLevel: 'ON_TRACK',
  note: null,
  expectedCompletionAt: null,
}
const openItem: ReportItem = {
  ...submittedItem,
  taskId: 'task-2',
  roundId: 'round-2',
  title: 'Viết báo cáo',
  taskStatus: 'IN_PROGRESS',
  timeliness: 'NOT_DUE',
  timelinessLabel: 'Chưa đến hạn',
  submission: null,
  declaredResult: 'RESULT_MISSING',
  prefilled: false,
  riskLevel: null,
}
const report = (overrides: Partial<MyReportResponse> = {}): MyReportResponse => ({
  serverTime: '2026-11-18T03:00:00Z',
  cycle: cycle(3),
  state: 'NOT_RESPONDED',
  revisionNo: null,
  status: null,
  submittedAt: null,
  canEdit: true,
  lockReason: null,
  items: [submittedItem, openItem],
  backlog: [],
  completeness: { requiredCount: 2, respondedCount: 0, missingTaskIds: ['task-1', 'task-2'], isComplete: false },
  submittedLabel: null,
  version: null,
  ...overrides,
})
const setup = (extra: ApiRoute[], data = report()) => mockApi([...extra, ['GET', API, data], ...shellRoutes(AN, GroupRole.MEMBER)])
const itemOf = (title: string) => within(screen.getByRole('heading', { name: title }).closest('li') as HTMLElement)

describe('S06 — Cập nhật checkpoint', () => {
  it('xác nhận hàng loạt task đã nộp + chọn nguy cơ + gửi: một request, có Idempotency-Key, quay lại màn gọi', async () => {
    const api = setup([['POST', `${API}/submit`, report({ state: 'SUBMITTED', submittedLabel: 'Đã gửi đầy đủ', version: 1 })]])
    const user = userEvent.setup()
    renderApp(PATH)

    expect(await screen.findByRole('dialog', { name: 'Cập nhật checkpoint' })).toBeInTheDocument()
    expect(await screen.findByText('Đã có phản hồi 0/2 task')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'PR #7' })).toHaveAttribute('href', 'https://github.com/x/y/pull/7')
    expect(screen.getByText(/chưa phải TL đã chấp nhận/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Xác nhận 1 task đã nộp kết quả' }))
    expect(screen.getByText('Đã có phản hồi 1/2 task')).toBeInTheDocument()
    await user.click(itemOf('Viết báo cáo').getByRole('radio', { name: 'Có nguy cơ trễ' }))
    await user.type(itemOf('Viết báo cáo').getByLabelText('Tiến độ / trở ngại'), 'Còn thiếu dữ liệu')
    await user.click(screen.getByRole('button', { name: 'Gửi báo cáo tuần' }))

    await waitFor(() => expect(api.writes('POST', `${API}/submit`)).toHaveLength(1))
    const call = api.writes('POST', `${API}/submit`)[0]
    expect(call.headers.get('Idempotency-Key')).toBeTruthy()
    expect(call.body).toMatchObject({
      version: null,
      items: [
        { taskId: 'task-1', declaredResult: 'RESULT_SUBMITTED', riskLevel: 'ON_TRACK' },
        { taskId: 'task-2', declaredResult: 'RESULT_MISSING', riskLevel: 'AT_RISK', note: 'Còn thiếu dữ liệu' },
      ],
    })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Cập nhật checkpoint' })).not.toBeInTheDocument())
  })

  it('thiếu phản hồi: không gọi API, chỉ rõ task còn thiếu; lỗi server vẫn giữ dữ liệu đã nhập', async () => {
    const api = setup([['POST', `${API}/submit`, () => apiError(422, 'REPORT_INCOMPLETE', 'Báo cáo còn thiếu task', [{ field: 'items[task-2]', message: 'Task này còn thiếu' }])]])
    const user = userEvent.setup()
    renderApp(PATH)
    await screen.findByRole('button', { name: 'Gửi báo cáo tuần' })

    await user.click(screen.getByRole('button', { name: 'Gửi báo cáo tuần' }))
    expect(api.writes('POST', `${API}/submit`)).toHaveLength(0)
    expect(screen.getAllByText('Hãy chọn tình hình công việc cho task này.')).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Xác nhận 1 task đã nộp kết quả' }))
    await user.click(itemOf('Viết báo cáo').getByRole('radio', { name: 'Cần TL hỗ trợ' }))
    expect(itemOf('Viết báo cáo').getByRole('link', { name: 'Gửi đề nghị riêng cho TL' })).toHaveAttribute('href', `/groups/${GROUP_ID}/tasks/task-2/difficulty`)
    await user.type(itemOf('Viết báo cáo').getByLabelText('Tiến độ / trở ngại'), 'Kẹt DB')
    await user.click(screen.getByRole('button', { name: 'Gửi báo cáo tuần' }))

    expect(await screen.findByText('Task này còn thiếu')).toBeInTheDocument()
    expect(itemOf('Viết báo cáo').getByLabelText('Tiến độ / trở ngại')).toHaveValue('Kẹt DB')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('sau giờ chốt chỉ xem: hiện lý do khóa của server, không có nút lưu/gửi', async () => {
    setup([], report({ canEdit: false, lockReason: { code: 'CYCLE_CLOSED', message: 'Kỳ đã chốt, bản báo cáo chỉ để xem' }, state: 'SUBMITTED', submittedLabel: 'Đã gửi đầy đủ' }))
    renderApp(PATH)
    expect(await screen.findByText('Kỳ đã chốt, bản báo cáo chỉ để xem')).toBeInTheDocument()
    expect(screen.getByText('Đã gửi đầy đủ')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gửi báo cáo tuần' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Lưu nháp' })).not.toBeInTheDocument()
  })

  it('kỳ không có task bắt buộc: giải thích, không mở form rỗng', async () => {
    setup([], report({ items: [] }))
    renderApp(PATH)
    expect(await screen.findByText('Kỳ này chưa có task cần cập nhật')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi báo cáo tuần' })).toBeDisabled()
  })
})
