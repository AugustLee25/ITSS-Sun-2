import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type Page, type PointPolicyPreviewResponse, type TaskSummary } from '@/api/types'
import { activeFilterCount, filtersToParams, parseFilters, toListQuery } from './taskQuery'
import { AN, BINH, CYCLE3_REF, GROUP_ID, MEMBERS, MINH, cycle, taskDetail, taskSummary } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const page = (items: TaskSummary[]): Page<TaskSummary> => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 })
const cycles = {
  items: [cycle(4, { label: 'Kỳ 4 · 20/11 22:00 → 27/11 22:00' }), cycle(3, { label: CYCLE3_REF.label }), cycle(2)],
  page: 0,
  size: 30,
  totalItems: 3,
  totalPages: 1,
}
const commonRoutes = (role: GroupRole) => [
  ['GET', `/groups/${GROUP_ID}/members`, { items: MEMBERS }],
  ['GET', `/groups/${GROUP_ID}/checkpoint-cycles`, cycles],
  ...shellRoutes(role === GroupRole.LEAD ? MINH : AN, role),
] as Parameters<typeof mockApi>[0]

describe('taskQuery', () => {
  it('đọc/ghi bộ lọc từ URL, bỏ giá trị lạ, đổi ngày deadline (múi giờ nhóm) sang UTC', () => {
    const filters = parseFilters(new URLSearchParams('tab=MINE&difficulty=HARD&plan=BAD&from=2026-11-20&to=2026-11-21&page=2&q=%20api%20'))
    expect(filters).toMatchObject({ tab: 'MINE', difficulty: 'HARD', planStatus: '', from: '2026-11-20', page: 2, q: ' api ' })
    expect(activeFilterCount(filters)).toBe(4)
    expect(filtersToParams(filters).toString()).toBe('tab=MINE&difficulty=HARD&q=api&from=2026-11-20&to=2026-11-21&page=2')
    expect(toListQuery(filters, 'Asia/Ho_Chi_Minh')).toMatchObject({
      tab: 'MINE',
      difficulty: 'HARD',
      q: 'api',
      deadlineFrom: '2026-11-19T17:00:00.000Z',
      deadlineTo: '2026-11-21T16:59:59.000Z',
      page: 2,
    })
    expect(parseFilters(new URLSearchParams('tab=XYZ')).tab).toBe('ALL')
  })
})

describe('S04 — danh sách công việc', () => {
  it('Member: thẻ có hai nhãn kế hoạch/thực hiện tách bạch + nhãn đúng hạn do server; đổi tab gọi lại API; không có "Giao task"', async () => {
    const api = mockApi([
      [
        'GET',
        `/groups/${GROUP_ID}/tasks`,
        (call) => (call.search.get('tab') === 'MINE' ? page([taskSummary({ title: 'Việc của tôi' })]) : page([taskSummary(), taskSummary({ id: 'task-2', title: 'Viết báo cáo', currentExecutor: BINH, originalAssignee: BINH })])),
      ],
      ...commonRoutes(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/tasks`)

    const card = (await screen.findByRole('link', { name: 'Thiết kế API' })).closest('article') as HTMLElement
    expect(card).toHaveAttribute('class', expect.stringContaining('card'))
    expect(within(card).getByText('Kế hoạch: Đã duyệt')).toBeInTheDocument()
    expect(within(card).getByText('Thực hiện: Đã nộp — chờ TL duyệt')).toBeInTheDocument()
    expect(within(card).getByText('Nộp đúng hạn — chờ TL duyệt')).toBeInTheDocument()
    expect(within(card).getByText('10,0000 điểm %')).toBeInTheDocument()
    expect(within(card).getByText('Khó')).toBeInTheDocument()
    expect(within(card).getByText('20/11/2026, 20:00')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Giao task' })).not.toBeInTheDocument()
    expect(screen.queryByText('Cân đối phân công')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Của tôi' }))
    expect(await screen.findByRole('link', { name: 'Việc của tôi' })).toBeInTheDocument()
    expect(api.calls.some((c) => c.path.endsWith('/tasks') && c.search.get('tab') === 'MINE')).toBe(true)
  })

  it('bộ lọc: độ khó + ngày deadline gửi đúng query; "không có task phù hợp" có nút xóa bộ lọc', async () => {
    const api = mockApi([
      ['GET', `/groups/${GROUP_ID}/tasks`, (call) => (call.search.get('difficulty') ? page([]) : page([taskSummary()]))],
      ...commonRoutes(GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/tasks`)
    await screen.findByRole('link', { name: 'Thiết kế API' })

    await user.click(screen.getByRole('button', { name: 'Bộ lọc' }))
    await user.selectOptions(screen.getByLabelText('Độ khó'), 'HARD')
    expect(await screen.findByText('Không có task phù hợp')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Deadline từ ngày'), { target: { value: '2026-11-20' } })
    await waitFor(() => expect(api.calls.some((c) => c.search.get('deadlineFrom') === '2026-11-19T17:00:00.000Z')).toBe(true))
    expect(api.calls.some((c) => c.search.get('difficulty') === 'HARD')).toBe(true)

    await user.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }))
    expect(await screen.findByRole('link', { name: 'Thiết kế API' })).toBeInTheDocument()
  })

  it('chưa có task: Member được giải thích, TL có CTA Giao task', async () => {
    mockApi([['GET', `/groups/${GROUP_ID}/tasks`, page([])], ...commonRoutes(GroupRole.LEAD)])
    renderApp(`/groups/${GROUP_ID}/tasks`)
    expect(await screen.findByRole('heading', { name: 'Chưa có task nào' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Giao task' }).length).toBe(2)
  })

  it('TL: bảng cân đối W_i / phần quỹ ban đầu (preview khi chưa khóa điểm), số liệu do server trả', async () => {
    const preview: PointPolicyPreviewResponse = {
      basis: 'INITIAL',
      nextVersionNo: 1,
      memberCount: 5,
      allocation: {
        totalPoints: { pointUnits: 1000000, percentLabel: '100,0000' },
        unallocatedPoints: { pointUnits: 0, percentLabel: '0,0000' },
        members: [
          {
            user: AN,
            initialPoints: { pointUnits: 200000, percentLabel: '20,0000' },
            reservePoints: { pointUnits: 0, percentLabel: '0,0000' },
            pricedPoints: { pointUnits: 200000, percentLabel: '20,0000' },
            weightSum: 6,
            taskCount: 3,
            tasks: [],
            warnings: [],
          },
          {
            user: BINH,
            initialPoints: { pointUnits: 200000, percentLabel: '20,0000' },
            reservePoints: { pointUnits: 200000, percentLabel: '20,0000' },
            pricedPoints: { pointUnits: 0, percentLabel: '0,0000' },
            weightSum: 0,
            taskCount: 0,
            tasks: [],
            warnings: ['W_i = 0: giữ B_i làm quỹ chưa phân bổ'],
          },
        ],
      },
      sumCheck: { expectedUnits: 1000000, actualUnits: 1000000, ok: true },
      blockingIssues: [{ code: 'POLICY_TASKS_NOT_APPROVED', message: 'Task "Viết báo cáo" chưa có kế hoạch được duyệt.', taskId: 't', userId: null }],
      canActivate: false,
    }
    mockApi([
      ['GET', `/groups/${GROUP_ID}/tasks`, page([taskSummary()])],
      ['GET', `/groups/${GROUP_ID}/point-policy`, { policy: null, active: false, statusLabel: 'Chưa có phương án điểm đã duyệt', allocation: null }],
      ['POST', `/groups/${GROUP_ID}/point-policy/preview`, preview],
      ...commonRoutes(GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/tasks`)
    await user.click(await screen.findByRole('button', { name: 'Xem bảng cân đối' }))
    const table = await screen.findByRole('table')
    const rowAn = within(table).getByRole('row', { name: /An/ })
    expect(within(rowAn).getByText('6')).toBeInTheDocument()
    expect(within(rowAn).getAllByText('20,0000 điểm %').length).toBe(2)
    expect(within(table).getByText('W_i = 0: giữ B_i làm quỹ chưa phân bổ')).toBeInTheDocument()
    expect(screen.getByText('Chưa có phương án điểm đã duyệt')).toBeInTheDocument()
    expect(screen.getByText('Task "Viết báo cáo" chưa có kế hoạch được duyệt.')).toBeInTheDocument()
  })
})

describe('S04 — TL giao task', () => {
  async function openDialog() {
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/tasks`)
    await user.click((await screen.findAllByRole('button', { name: 'Giao task' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Giao task mới' })
    return { user, dialog }
  }

  function fill(dialog: HTMLElement) {
    return async (user: ReturnType<typeof userEvent.setup>) => {
      await user.type(within(dialog).getByLabelText(/^Tên task/), 'Thiết kế giao diện')
      await user.selectOptions(within(dialog).getByLabelText(/^Người phụ trách/), AN.userId)
      await user.selectOptions(within(dialog).getByLabelText(/^Tuần/), 'cyc-3')
      await user.type(within(dialog).getByLabelText(/^Phạm vi công việc/), 'Thiết kế 5 màn hình')
      await user.type(within(dialog).getByLabelText(/^Đầu ra mong đợi/), 'File Figma')
      fireEvent.change(within(dialog).getByLabelText(/^Ngày bắt đầu/), { target: { value: '2026-11-14' } })
      fireEvent.change(within(dialog).getByLabelText(/^Ngày deadline/), { target: { value: '2026-11-20' } })
      await user.selectOptions(within(dialog).getByLabelText(/^Độ khó/), 'HARD')
    }
  }

  it('bắt buộc đủ trường; gửi đúng body (giờ nhóm → UTC), có Idempotency-Key, thành công thì đóng modal và báo', async () => {
    const api = mockApi([
      ['GET', `/groups/${GROUP_ID}/tasks`, page([])],
      ['POST', `/groups/${GROUP_ID}/tasks`, taskDetail({ title: 'Thiết kế giao diện' })],
      ...commonRoutes(GroupRole.LEAD),
    ])
    const { user, dialog } = await openDialog()

    await user.click(within(dialog).getByRole('button', { name: 'Giao task' }))
    expect(await within(dialog).findByText('Tên task cần từ 3 đến 120 ký tự')).toBeInTheDocument()
    expect(within(dialog).getByText('Hãy chọn người phụ trách')).toBeInTheDocument()
    expect(api.writes('POST', `/groups/${GROUP_ID}/tasks`)).toHaveLength(0)

    await fill(dialog)(user)
    await user.click(within(dialog).getByRole('button', { name: 'Giao task' }))

    await waitFor(() => expect(api.writes('POST', `/groups/${GROUP_ID}/tasks`)).toHaveLength(1))
    const call = api.writes('POST', `/groups/${GROUP_ID}/tasks`)[0]!
    expect(call.body).toEqual({
      title: 'Thiết kế giao diện',
      assigneeId: AN.userId,
      cycleId: 'cyc-3',
      description: 'Thiết kế 5 màn hình',
      expectedOutput: 'File Figma',
      startAt: '2026-11-14T01:00:00.000Z',
      deadline: '2026-11-20T13:00:00.000Z',
      difficulty: 'HARD',
    })
    expect(call.headers.get('Idempotency-Key')).toBeTruthy()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Giao task mới' })).not.toBeInTheDocument())
    expect(screen.getByText(/Đã giao task Thiết kế giao diện/)).toBeInTheDocument()
  })

  it('lỗi 422 của server gắn vào đúng trường, modal và dữ liệu nhập được giữ lại, thử lại được', async () => {
    let attempts = 0
    const api = mockApi([
      ['GET', `/groups/${GROUP_ID}/tasks`, page([])],
      [
        'POST',
        `/groups/${GROUP_ID}/tasks`,
        () => {
          attempts += 1
          return attempts === 1
            ? apiError(422, 'DEADLINE_OUTSIDE_CYCLE', 'Deadline phải nằm trong kỳ 13/11 22:00 → 20/11 22:00.', [{ field: 'deadline', message: 'Nằm ngoài kỳ đã chọn' }])
            : taskDetail()
        },
      ],
      ...commonRoutes(GroupRole.LEAD),
    ])
    const { user, dialog } = await openDialog()
    await fill(dialog)(user)
    await user.click(within(dialog).getByRole('button', { name: 'Giao task' }))

    expect(await within(dialog).findByText('Nằm ngoài kỳ đã chọn')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/^Tên task/)).toHaveValue('Thiết kế giao diện')
    expect(screen.getByRole('dialog', { name: 'Giao task mới' })).toBeInTheDocument()

    fireEvent.change(within(dialog).getByLabelText(/^Ngày deadline/), { target: { value: '2026-11-19' } })
    await user.click(within(dialog).getByRole('button', { name: 'Giao task' }))
    await waitFor(() => expect(api.writes('POST', `/groups/${GROUP_ID}/tasks`)).toHaveLength(2))
    // body khác → khóa idempotency mới (không dùng lại khóa của lần bị từ chối)
    const [first, second] = api.writes('POST', `/groups/${GROUP_ID}/tasks`)
    expect(first!.headers.get('Idempotency-Key')).not.toBe(second!.headers.get('Idempotency-Key'))
  })

  it('bấm đúp chỉ gửi một lần', async () => {
    let resolve: () => void = () => undefined
    const gate = new Promise<void>((r) => (resolve = r))
    const api = mockApi([
      ['GET', `/groups/${GROUP_ID}/tasks`, page([])],
      ['POST', `/groups/${GROUP_ID}/tasks`, taskDetail()],
      ...commonRoutes(GroupRole.LEAD),
    ])
    // giữ phản hồi POST lại đến khi mở cổng, để mô phỏng đang gửi dở
    const original = globalThis.fetch
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      if ((init?.method ?? 'GET') === 'POST') await gate
      return original(input, init)
    })
    const { user, dialog } = await openDialog()
    await fill(dialog)(user)
    const submit = within(dialog).getByRole('button', { name: 'Giao task' })
    await user.dblClick(submit)
    resolve()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Giao task mới' })).not.toBeInTheDocument())
    expect(api.writes('POST', `/groups/${GROUP_ID}/tasks`)).toHaveLength(1)
  })
})
