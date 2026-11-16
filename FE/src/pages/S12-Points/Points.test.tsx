import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Difficulty, GroupRole, PointTaskSource, PointTaskStatus, type MyPointsResponse } from '@/api/types'
import { AN, BINH, CYCLE3_REF, GROUP_ID, MINH } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes, type ApiRoute } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const P = `/groups/${GROUP_ID}/points`
const amount = (units: number, label: string) => ({ pointUnits: units, percentLabel: label })
const zero = amount(0, '0,0000')

/** Bình: phần ban đầu 20%, chưa được xác nhận điểm nào, nhận làm thay task Khó 4% → có thể đạt 14% (spec 0.9). */
const myPoints = (overrides: Partial<MyPointsResponse> = {}): MyPointsResponse => ({
  policyActive: true,
  statusLabel: null,
  memberCount: 5,
  initialPoints: amount(200000, '20,0000'),
  confirmedPoints: zero,
  pendingPoints: amount(140000, '14,0000'),
  potentialPoints: amount(140000, '14,0000'),
  transferredOut: { points: amount(100000, '10,0000'), taskCount: 1 },
  takenOver: { points: amount(40000, '4,0000'), taskCount: 1 },
  pendingTransfer: { heldPoints: zero, openRequestCount: 0 },
  unallocatedReservePoints: zero,
  goalCompletionPercentLabel: '0,0000',
  ...overrides,
})

const taskRow = {
  taskId: 'task-9',
  title: 'Thiết kế API',
  difficulty: Difficulty.HARD,
  points: amount(40000, '4,0000'),
  originalAssignee: AN,
  currentRecipient: BINH,
  source: PointTaskSource.TAKEN_OVER,
  cycle: CYCLE3_REF,
  deadline: '2026-11-27T13:00:00Z',
  status: PointTaskStatus.IN_PROGRESS,
  statusLabel: 'Đang làm',
  transferredTo: null,
  heldForRequest: false,
  helpRequestId: 'help-1',
  workId: 'work-1',
}
const page = <T,>(items: T[]) => ({ items, page: 0, size: 10, totalItems: items.length, totalPages: 1 })

const routes = (me: MyPointsResponse | (() => unknown), extra: ApiRoute[] = []): ApiRoute[] => [
  ['GET', `/groups/${GROUP_ID}/points/me`, me],
  ['GET', `/groups/${GROUP_ID}/points/me/tasks`, page([taskRow])],
  ['GET', `/groups/${GROUP_ID}/points/me/simulation-candidates`, {
    transferable: [{ taskId: 't3', title: 'Task Khó của tôi', difficulty: Difficulty.HARD, points: amount(100000, '10,0000'), owner: BINH, helpRequestId: null }],
    takeable: [{ taskId: 't9', title: 'Task của An', difficulty: Difficulty.MEDIUM, points: amount(40000, '4,0000'), owner: AN, helpRequestId: 'help-1' }],
  }],
  ...extra,
  ...shellRoutes(BINH, GroupRole.MEMBER),
]

describe('S12 — Điểm của tôi', () => {
  it('phân biệt "Đã xác nhận 0" với "Có thể đạt 14"; chuyển đi/nhận thêm không cộng vào tổng; danh sách task có điểm', async () => {
    mockApi(routes(myPoints()))
    renderApp(P)

    const confirmed = (await screen.findByText('Đã được TL xác nhận')).closest('div') as HTMLElement
    expect(within(confirmed).getByText('0,0000 điểm %')).toBeInTheDocument()
    expect(within(confirmed).getByText(/Chưa có kết quả nào được TL chấp nhận nên số này là 0/)).toBeInTheDocument()
    const potential = screen.getByText('Có thể đạt theo phân công hiện tại').closest('div') as HTMLElement
    expect(within(potential).getByText('14,0000 điểm %')).toBeInTheDocument()
    expect(screen.getByText('20,0000 điểm % trong nhóm 5 người')).toBeInTheDocument()
    expect(screen.getByText('Giảm 10,0000 điểm % khả năng nhận · 1 task')).toBeInTheDocument()
    expect(screen.getByText('Tăng 4,0000 điểm % khả năng nhận · 1 task')).toBeInTheDocument()
    expect(screen.getByText(/đã nằm trong "Có thể đạt", không cộng thêm/)).toBeInTheDocument()
    expect(screen.getByText(/Hoàn thành 0,0000% phần được giao ban đầu/)).toBeInTheDocument()

    const taskLink = await screen.findByRole('link', { name: 'Thiết kế API' })
    expect(taskLink).toHaveAttribute('href', `/groups/${GROUP_ID}/tasks/task-9`)
    expect(within(taskLink.closest('li') as HTMLElement).getByText('Nhận làm thay')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem phần việc hỗ trợ' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/help-1/work`)
  })

  it('chưa có phương án điểm → "Chưa có phương án điểm đã duyệt", không tải danh sách/mô phỏng', async () => {
    const api = mockApi(routes(myPoints({ policyActive: false, statusLabel: 'Chưa có phương án điểm đã duyệt', initialPoints: null })))
    renderApp(P)
    expect(await screen.findByText('Chưa có phương án điểm đã duyệt')).toBeInTheDocument()
    expect(api.calls.some((c) => c.path.includes('/points/me/tasks') || c.path.includes('simulation'))).toBe(false)
    expect(screen.queryByText('Đã được TL xác nhận')).not.toBeInTheDocument()
  })

  it('lỗi mạng → báo lỗi + thử lại, không thay bằng số 0 giả', async () => {
    let online = false
    mockApi(
      routes(() => {
        if (!online) throw new TypeError('Failed to fetch')
        return myPoints({ confirmedPoints: amount(100000, '10,0000') })
      }),
    )
    const user = userEvent.setup()
    renderApp(P)
    expect(await screen.findByText('Không tải được dữ liệu điểm', undefined, { timeout: 8000 })).toBeInTheDocument()
    expect(screen.queryByText('0,0000 điểm %')).not.toBeInTheDocument()
    online = true
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))
    expect(await screen.findByText('10,0000 điểm %', { selector: 'dd' })).toBeInTheDocument()
  }, 15_000)

  it('mô phỏng mất task này / nhận task kia: POST chỉ đọc, hiện đúng các bước do server tính', async () => {
    const api = mockApi(
      routes(myPoints(), [
        [
          'POST',
          `/groups/${GROUP_ID}/points/me/simulate`,
          {
            confirmedPoints: zero,
            startPotentialPoints: amount(200000, '20,0000'),
            endPotentialPoints: amount(140000, '14,0000'),
            steps: [
              { kind: 'START', label: 'Hiện có thể đạt 20,0000%', taskId: null, delta: null, potentialAfter: amount(200000, '20,0000') },
              { kind: 'TRANSFER_OUT', label: 'Chuyển task 10,0000 điểm % → còn 10,0000%', taskId: 't3', delta: amount(-100000, '-10,0000'), potentialAfter: amount(100000, '10,0000') },
              { kind: 'TAKE_ON', label: 'Nhận task 4,0000 điểm % → có thể đạt 14,0000%', taskId: 't9', delta: amount(40000, '+4,0000'), potentialAfter: amount(140000, '14,0000') },
            ],
            notice: 'Đây là mô phỏng, không ghi điểm',
          },
        ],
      ]),
    )
    const user = userEvent.setup()
    renderApp(P)
    const run = await screen.findByRole('button', { name: 'Xem mô phỏng' })
    expect(run).toBeDisabled()
    await user.click(await screen.findByRole('checkbox', { name: /Task Khó của tôi/ }))
    await user.click(screen.getByRole('checkbox', { name: /Task của An/ }))
    await user.click(run)
    expect(await screen.findByText('Chuyển task 10,0000 điểm % → còn 10,0000%')).toBeInTheDocument()
    expect(screen.getByText('Nhận task 4,0000 điểm % → có thể đạt 14,0000%')).toBeInTheDocument()
    expect(screen.getByText('Đây là mô phỏng, không ghi điểm')).toBeInTheDocument()
    expect(api.writes('POST', /simulate/)[0]?.body).toEqual({ transferOutTaskIds: ['t3'], takeOnTaskIds: ['t9'] })
  })

  it('mô phỏng task không còn hợp lệ (422) → thông báo và tải lại danh sách', async () => {
    const api = mockApi(
      routes(myPoints(), [['POST', `/groups/${GROUP_ID}/points/me/simulate`, () => apiError(422, 'SIMULATION_INVALID_TASK', 'Task đã có người nhận.')]]),
    )
    const user = userEvent.setup()
    renderApp(`${P}?taskId=t9`)
    await user.click(await screen.findByRole('button', { name: 'Xem mô phỏng' }))
    expect(await screen.findByText(/không còn mô phỏng được/)).toBeInTheDocument()
    await waitFor(() => expect(api.calls.filter((c) => c.path.endsWith('simulation-candidates')).length).toBeGreaterThan(1))
  })
})

describe('S12 — Lịch sử & quỹ nhóm', () => {
  const entry = {
    id: 'led-1',
    group: 'POINTS_RECORDED',
    kind: 'AWARD',
    occurredAt: '2026-11-28T02:00:00Z',
    task: { id: 'task-1', title: 'Thiết kế API' },
    delta: amount(100000, '+10,0000'),
    runningTotal: amount(300000, '30,0000'),
    source: 'TAKEN_OVER',
    approvedBy: MINH,
    label: 'Đã ghi nhận +10,0000 điểm % từ task Thiết kế API',
    reason: null,
    ledgerEntryId: 'led-1',
    workId: 'work-1',
  }
  const assignment = { ...entry, id: 'asg-1', group: 'ASSIGNMENT', kind: 'ASSIGNMENT_IN', delta: null, runningTotal: null, ledgerEntryId: null, label: 'Bạn nhận làm thay task Thiết kế API' }

  it('sổ điểm tách "Điểm đã ghi" với sự kiện "Phân công"; mở chi tiết khoản bằng link riêng', async () => {
    mockApi([
      ['GET', `/groups/${GROUP_ID}/points/me/ledger`, page([entry, assignment])],
      [
        'GET',
        `/groups/${GROUP_ID}/points/ledger/led-1`,
        {
          entry,
          task: entry.task,
          budgetPoints: amount(100000, '10,0000'),
          originalAssignee: AN,
          submission: { id: 'sub-1', submissionNo: 1, evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/9', label: 'PR #9' }] },
          review: { decision: 'ACCEPTED' },
          contribution: { status: 'CONFIRMED' },
          settlement: { id: 's-1', approvedBy: MINH, approvedAt: '2026-11-28T02:00:00Z', revokedAt: null, revokeReason: null },
          workId: 'work-1',
          entryType: 'AWARD',
          reversesEntryId: null,
          reversedByEntryId: null,
        },
      ],
      ...shellRoutes(BINH, GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(`${P}?tab=history`)
    expect(await screen.findByText('Đã ghi nhận +10,0000 điểm % từ task Thiết kế API')).toBeInTheDocument()
    expect(screen.getByText('Bạn nhận làm thay task Thiết kế API')).toBeInTheDocument()
    expect(screen.getByText(/Sự kiện phân công, không phải điểm đã ghi/)).toBeInTheDocument()
    expect(screen.getByText(/Tổng lũy kế 30,0000 điểm %/)).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Đã ghi nhận +10,0000 điểm % từ task Thiết kế API' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('link', { name: 'PR #9' })).toBeInTheDocument()
    expect(within(dialog).getByText('Chấp nhận')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'Xem phần việc hỗ trợ' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/work-1/work`)
    await user.click(within(dialog).getByRole('button', { name: 'Đóng' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('quỹ điểm nhóm: tổng, đã xác nhận, chưa xác nhận, chưa phân bổ; không xếp hạng', async () => {
    mockApi([
      [
        'GET',
        `/groups/${GROUP_ID}/points/summary`,
        { policyActive: true, statusLabel: null, totalPoints: amount(1000000, '100,0000'), confirmedPoints: amount(300000, '30,0000'), pendingPoints: amount(700000, '70,0000'), unallocatedPoints: zero },
      ],
      ...shellRoutes(BINH, GroupRole.MEMBER),
    ])
    renderApp(`${P}?tab=fund`)
    expect(await screen.findByText('100,0000 điểm %')).toBeInTheDocument()
    expect(screen.getByText('30,0000 điểm %')).toBeInTheDocument()
    expect(screen.getByText('70,0000 điểm %')).toBeInTheDocument()
    expect(screen.getByText(/Không có xếp hạng thành viên/)).toBeInTheDocument()
  })
})
