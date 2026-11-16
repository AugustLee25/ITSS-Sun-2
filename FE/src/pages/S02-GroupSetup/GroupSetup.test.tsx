import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type CreateGroupResponse, type InvitePreview, type SchedulePreviewResponse } from '@/api/types'
import { GROUP_ID, INVITE, MINH, SCHEDULE, cycle, dashboard, groupFor } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes } from '@/test/renderApp'
import { extractInviteCode, toPreviewRequest } from './scheduleForm'

afterEach(() => vi.unstubAllGlobals())

const PREVIEW: SchedulePreviewResponse = {
  summaryLabel: 'Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00',
  reminderBeforeMinutes: 420,
  cycles: [
    {
      sequenceNo: 1,
      periodStartAt: '2026-11-14T17:00:00Z',
      opensAt: '2026-11-14T17:00:00Z',
      reminderAt: '2026-11-20T08:00:00Z',
      closesAt: '2026-11-20T15:00:00Z',
      periodHours: 142,
      isTransition: true,
      label: 'Kỳ 1 · 15/11 00:00 → 20/11 22:00',
    },
  ],
  warnings: [{ code: 'TRANSITION_CYCLE_SHORT', message: 'Kỳ 1 chỉ dài 142 giờ vì nhóm bắt đầu giữa tuần.' }],
}

const CREATED: CreateGroupResponse = {
  group: groupFor(GroupRole.LEAD),
  schedule: SCHEDULE,
  currentCycle: cycle(3),
  invite: INVITE,
}

describe('helper S02', () => {
  it('extractInviteCode nhận mã trần, /join/CODE, URL đầy đủ và ?invite=', () => {
    expect(extractInviteCode(' K7P4Q9XD ')).toBe('K7P4Q9XD')
    expect(extractInviteCode('/join/K7P4Q9XD')).toBe('K7P4Q9XD')
    expect(extractInviteCode('https://hanare.example/join/K7P4Q9XD?x=1')).toBe('K7P4Q9XD')
    expect(extractInviteCode('https://hanare.example/login?invite=K7P4Q9XD')).toBe('K7P4Q9XD')
    expect(extractInviteCode('mã sai!')).toBe('')
  })

  it('toPreviewRequest: thiếu/sai giờ → null; có groupId thì không gửi startDate', () => {
    expect(toPreviewRequest({ weekday: '5', cutoffTime: '', timezone: 'UTC', window: '' }, {})).toBeNull()
    expect(toPreviewRequest({ weekday: '8', cutoffTime: '22:00', timezone: 'UTC', window: '' }, {})).toBeNull()
    expect(toPreviewRequest({ weekday: '7', cutoffTime: '20:00', timezone: 'UTC', window: '1440' }, { groupId: 'g' })).toEqual({
      weekday: 7,
      cutoffTime: '20:00',
      timezone: 'UTC',
      reportingWindowMinutes: 1440,
      groupId: 'g',
    })
  })
})

describe('S02 — tạo nhóm (wizard 2 bước)', () => {
  it('bước 1 kiểm tra dữ liệu, bước 2 xem trước lịch do server dựng, bắt buộc xác nhận lịch đã thống nhất, tạo xong hiện mã/link mời', async () => {
    const api = mockApi([
      ['POST', '/checkpoint-schedules/preview', PREVIEW],
      ['POST', '/groups', CREATED],
      ['GET', `/groups/${GROUP_ID}/dashboard`, dashboard(GroupRole.LEAD)],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp('/groups/new')

    // Bước 1: tên quá ngắn + deadline trước ngày bắt đầu
    await user.type(await screen.findByLabelText(/^Tên nhóm/), 'Ab')
    fireEvent.change(screen.getByLabelText(/^Ngày bắt đầu/), { target: { value: '2026-11-15' } })
    fireEvent.change(screen.getByLabelText(/^Deadline tổng thể/), { target: { value: '2026-11-01' } })
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    expect(await screen.findByText('Tên nhóm cần từ 3 đến 80 ký tự')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Tên nhóm/), 'c CSDL')
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    expect(await screen.findByText('Deadline không được trước ngày bắt đầu')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/^Deadline tổng thể/), { target: { value: '2026-12-20' } })
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    // Bước 2: preview do server dựng, FE không tự cộng trừ giờ
    expect(await screen.findByRole('heading', { name: /Lịch checkpoint/ })).toBeInTheDocument()
    expect(await screen.findByText('Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00')).toBeInTheDocument()
    expect(screen.getByText(/Nhắc trước 7 giờ/)).toBeInTheDocument()
    expect(screen.getByText('Kỳ 1 chỉ dài 142 giờ vì nhóm bắt đầu giữa tuần.')).toBeInTheDocument()
    const previewCall = api.writes('POST', '/checkpoint-schedules/preview')[0]
    expect(previewCall?.body).toMatchObject({ startDate: '2026-11-15', weekday: 5, cutoffTime: '22:00', timezone: 'Asia/Ho_Chi_Minh', reportingWindowMinutes: null })

    // Chưa tick xác nhận → không gửi
    await user.click(screen.getByRole('button', { name: 'Tạo nhóm' }))
    expect(await screen.findByText('Hãy xác nhận đây là lịch nhóm đã thống nhất')).toBeInTheDocument()
    expect(api.writes('POST', '/groups')).toHaveLength(0)

    // Quay lại giữ nguyên dữ liệu bước 1
    await user.click(screen.getByRole('button', { name: 'Quay lại' }))
    expect(screen.getByLabelText(/^Tên nhóm/)).toHaveValue('Abc CSDL')
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))

    await user.click(await screen.findByRole('checkbox', { name: /Đây là lịch cả nhóm đã thống nhất/ }))
    await user.click(screen.getByRole('button', { name: 'Tạo nhóm' }))

    const dialog = await screen.findByRole('dialog', { name: /Đã tạo nhóm Bài tập lớn CSDL/ })
    expect(within(dialog).getByDisplayValue('K7P4Q9XD')).toBeInTheDocument()
    expect(within(dialog).getByDisplayValue(`${window.location.origin}/join/K7P4Q9XD`)).toBeInTheDocument()
    const create = api.writes('POST', '/groups')
    expect(create).toHaveLength(1)
    expect(create[0]?.body).toEqual({
      name: 'Abc CSDL',
      courseName: null,
      startDate: '2026-11-15',
      deadline: '2026-12-20',
      schedule: { weekday: 5, cutoffTime: '22:00', timezone: 'Asia/Ho_Chi_Minh', reportingWindowMinutes: null },
      scheduleAgreed: true,
    })
    expect(create[0]?.headers.get('Idempotency-Key')).toBeTruthy()

    await user.click(within(dialog).getByRole('button', { name: 'Đi tới nhóm' }))
    expect(await screen.findByRole('heading', { name: 'Bài tập lớn CSDL', level: 1 })).toBeInTheDocument()
  })

  it('lỗi server (deadline nhóm sai) đưa về bước 1, giữ dữ liệu và gắn lỗi vào trường deadline', async () => {
    mockApi([
      ['POST', '/checkpoint-schedules/preview', PREVIEW],
      ['POST', '/groups', apiError(422, 'GROUP_DEADLINE_INVALID', 'Deadline phải sau ngày bắt đầu.')],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp('/groups/new')
    await user.type(await screen.findByLabelText(/^Tên nhóm/), 'Nhóm thử')
    fireEvent.change(screen.getByLabelText(/^Ngày bắt đầu/), { target: { value: '2026-11-15' } })
    fireEvent.change(screen.getByLabelText(/^Deadline tổng thể/), { target: { value: '2026-12-20' } })
    await user.click(screen.getByRole('button', { name: 'Tiếp tục' }))
    await user.click(await screen.findByRole('checkbox', { name: /Đây là lịch cả nhóm đã thống nhất/ }))
    await user.click(screen.getByRole('button', { name: 'Tạo nhóm' }))

    expect(await screen.findByText('Deadline phải sau ngày bắt đầu.', { selector: 'p span' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^Tên nhóm/)).toHaveValue('Nhóm thử')
  })
})

describe('S02 — tham gia nhóm', () => {
  const PREVIEW_INVITE: InvitePreview = {
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

  it('/join/:code tự kiểm tra lời mời, hiện nhóm/trưởng nhóm/số thành viên rồi tham gia → S03', async () => {
    const api = mockApi([
      ['GET', `/invites/${INVITE.code}`, PREVIEW_INVITE],
      ['POST', `/invites/${INVITE.code}/accept`, { group: groupFor(GroupRole.MEMBER) }],
      ['GET', `/groups/${GROUP_ID}/dashboard`, dashboard(GroupRole.MEMBER)],
      ...shellRoutes(MINH, GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(`/join/${INVITE.code}`)

    expect(await screen.findByRole('heading', { name: 'Bài tập lớn CSDL', level: 3 })).toBeInTheDocument()
    expect(screen.getByText('Minh')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tham gia nhóm' }))
    expect(await screen.findByRole('heading', { name: 'Bài tập lớn CSDL', level: 1 })).toBeInTheDocument()
    expect(api.writes('POST', `/invites/${INVITE.code}/accept`)).toHaveLength(1)
  })

  it('dán link mời vào ô nhập → tách mã; mã hết hạn hiện lý do và cách xử lý', async () => {
    const api = mockApi([
      ['GET', '/invites/K7P4Q9XD', apiError(410, 'INVITE_EXPIRED', 'Mã mời đã hết hạn.')],
      ...shellRoutes(MINH, GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp('/groups/join')
    await user.type(await screen.findByLabelText(/^Mã hoặc link mời/), 'https://hanare.example/join/K7P4Q9XD')
    await user.click(screen.getByRole('button', { name: 'Kiểm tra lời mời' }))
    expect(await screen.findByText('Mã mời đã hết hạn')).toBeInTheDocument()
    expect(screen.getByText(/Nhờ trưởng nhóm tạo mã mời mới/)).toBeInTheDocument()
    expect(api.calls.some((c) => c.path === '/invites/K7P4Q9XD')).toBe(true)
  })

  it('đã là thành viên: giải thích và dẫn tới nhóm, không có nút tham gia lại', async () => {
    mockApi([
      ['GET', `/invites/${INVITE.code}`, { ...PREVIEW_INVITE, alreadyMember: true, groupId: GROUP_ID }],
      ...shellRoutes(MINH, GroupRole.MEMBER),
    ])
    renderApp(`/join/${INVITE.code}`)
    expect(await screen.findByText('Bạn đã là thành viên của nhóm này')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tham gia nhóm' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Đi tới nhóm' })).toHaveAttribute('href', `/groups/${GROUP_ID}`)
  })
})

describe('S02 — cài đặt nhóm (TL đổi lịch checkpoint)', () => {
  it('TL: xem trước thay đổi (có groupId), phải xác nhận, lưu gửi Idempotency-Key; Member chỉ thấy lời giải thích', async () => {
    const api = mockApi([
      ['GET', `/groups/${GROUP_ID}/checkpoint-schedules`, { items: [SCHEDULE] }],
      ['GET', `/groups/${GROUP_ID}/invite`, INVITE],
      ['POST', '/checkpoint-schedules/preview', PREVIEW],
      ['POST', `/groups/${GROUP_ID}/checkpoint-schedules`, { schedule: { ...SCHEDULE, id: 'sch-2', versionNo: 2, weekday: 7 }, affectedCycles: [] }],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}/settings`)

    expect(await screen.findByText('Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00', { selector: 'p' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText(/^Ngày chốt trong tuần/), '7')
    expect(await screen.findByText('Kỳ 1 chỉ dài 142 giờ vì nhóm bắt đầu giữa tuần.')).toBeInTheDocument()
    expect(api.writes('POST', '/checkpoint-schedules/preview').at(-1)?.body).toMatchObject({ groupId: GROUP_ID, weekday: 7 })

    await user.click(screen.getByRole('button', { name: 'Lưu lịch mới' }))
    expect(await screen.findByText('Hãy xác nhận đây là lịch nhóm đã thống nhất')).toBeInTheDocument()
    await user.click(screen.getByRole('checkbox', { name: /Đây là lịch cả nhóm đã thống nhất/ }))
    await user.click(screen.getByRole('button', { name: 'Lưu lịch mới' }))

    await waitFor(() => expect(api.writes('POST', `/groups/${GROUP_ID}/checkpoint-schedules`)).toHaveLength(1))
    const save = api.writes('POST', `/groups/${GROUP_ID}/checkpoint-schedules`)[0]
    expect(save?.body).toEqual({ weekday: 7, cutoffTime: '22:00', timezone: 'Asia/Ho_Chi_Minh', reportingWindowMinutes: null, scheduleAgreed: true })
    expect(save?.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('Member không đổi được cài đặt nhóm', async () => {
    mockApi([...shellRoutes(MINH, GroupRole.MEMBER)])
    renderApp(`/groups/${GROUP_ID}/settings`)
    expect(await screen.findByText('Chỉ trưởng nhóm mới đổi được cài đặt nhóm')).toBeInTheDocument()
  })
})
