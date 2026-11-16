import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type CycleTaskRow, type LeadPanel, type ReviewQueueResponse } from '@/api/types'
import { targetHref } from './targets'
import { AN, BINH, CYCLE3_REF, GROUP_ID, MINH, cycle, dashboard, taskSummary } from '@/test/fixtures'
import { mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const dash = `/groups/${GROUP_ID}/dashboard`

describe('targetHref', () => {
  it('S03 + cycleId mở khu TL kiểm tra kỳ; S05 + planId mở bản đề xuất', () => {
    expect(targetHref({ screen: 'S03', params: { groupId: 'g', cycleId: 'c' } })).toBe('/groups/g?cycleId=c')
    expect(targetHref({ screen: 'S05', params: { groupId: 'g', taskId: 't', planId: 'p' } })).toBe('/groups/g/tasks/t?planId=p')
    expect(targetHref({ screen: 'S06', params: { groupId: 'g', cycleId: 'c' } })).toBe('/groups/g/checkpoints/c')
  })
})

describe('S03 — Tổng quan nhóm (Member)', () => {
  it('xếp theo thứ tự ưu tiên của spec; header có nhãn tình trạng + lý do, không chỉ màu', async () => {
    mockApi([['GET', dash, dashboard(GroupRole.MEMBER)], ...shellRoutes(AN, GroupRole.MEMBER)])
    renderApp(`/groups/${GROUP_ID}`)

    expect(await screen.findByRole('heading', { level: 1, name: 'Bài tập lớn CSDL' })).toBeInTheDocument()
    expect(screen.getByText('Cần xem xét')).toBeInTheDocument()
    expect(screen.getByText(/chưa phải kết luận về năng suất cá nhân/)).toBeInTheDocument()

    const order = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(order).toEqual([
      'Việc cần làm của bạn',
      'Công việc của tôi',
      'Checkpoint tiếp theo',
      'Tiến độ thành viên',
      'Việc có thể ảnh hưởng kế hoạch',
      'Hỗ trợ đang mở',
      'Điểm của tôi',
      'Hoạt động gần đây',
    ])
    // Member không thấy khu của TL
    expect(screen.queryByText('Dành cho trưởng nhóm')).not.toBeInTheDocument()
  })

  it('khối hành động chỉ hiện CTA có thật và dẫn tới S06; checkpoint hiện khung mở/chốt, nhắc 7 giờ và số đã/chưa phản hồi', async () => {
    mockApi([['GET', dash, dashboard(GroupRole.MEMBER)], ...shellRoutes(AN, GroupRole.MEMBER)])
    renderApp(`/groups/${GROUP_ID}`)

    const actions = (await screen.findByRole('heading', { name: 'Việc cần làm của bạn' })).closest('section') as HTMLElement
    expect(within(actions).getByRole('link', { name: 'Cập nhật checkpoint Kỳ 3' })).toHaveAttribute('href', `/groups/${GROUP_ID}/checkpoints/cyc-3`)
    expect(within(actions).getByText('3 mục')).toBeInTheDocument()

    const checkpoint = screen.getByRole('heading', { name: 'Checkpoint tiếp theo' }).closest('section') as HTMLElement
    expect(within(checkpoint).getByText(/Nhắc trước 7 giờ/)).toBeInTheDocument()
    expect(within(checkpoint).getByText('20/11/2026, 22:00')).toBeInTheDocument() // giờ chốt theo múi giờ nhóm
    expect(within(checkpoint).getByText('20/11/2026, 15:00')).toBeInTheDocument() // nhắc lúc 15:00
    expect(within(checkpoint).getByText('Chưa có cập nhật')).toBeInTheDocument()
    expect(within(checkpoint).getByText('2 đã cập nhật · 3 chưa có cập nhật')).toBeInTheDocument()
    expect(within(checkpoint).getByRole('link', { name: 'Cập nhật checkpoint' })).toBeInTheDocument()
  })

  it('khung tên thành viên có màu NHƯNG luôn kèm nhãn chữ + lý do/số task; chạm mở danh sách task của người đó', async () => {
    const row: CycleTaskRow = {
      taskId: 'task-9',
      roundId: 'round-9',
      roundNo: 1,
      title: 'Viết báo cáo',
      assignee: BINH,
      handedOverTo: null,
      deadline: '2026-11-19T13:00:00Z',
      difficulty: 'MEDIUM',
      taskStatus: 'IN_PROGRESS',
      timeliness: 'OVERDUE_NO_RESULT',
      timelinessLabel: 'Quá hạn, chưa có kết quả',
      isLate: true,
      submittedAt: null,
      review: null,
      completedAt: null,
      acceptedAt: null,
      completedLate: false,
      reported: false,
      declaredResult: null,
      riskLevel: null,
      reportNote: null,
      carryover: { required: false, reason: null, targetRoundId: null },
    }
    const api = mockApi([
      ['GET', dash, dashboard(GroupRole.MEMBER)],
      [
        'GET',
        `/groups/${GROUP_ID}/dashboard/members/${BINH.userId}`,
        { cycle: cycle(3), progress: dashboard().memberProgress[1], tasks: [row] },
      ],
      ...shellRoutes(AN, GroupRole.MEMBER),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}`)

    const members = (await screen.findByRole('heading', { name: 'Tiến độ thành viên' })).closest('section') as HTMLElement
    expect(within(members).getByText('Đã nộp đủ — chờ TL duyệt')).toBeInTheDocument()
    expect(within(members).getByText('3 chờ duyệt')).toBeInTheDocument()
    expect(within(members).getByText('Trễ 2 task')).toBeInTheDocument()
    expect(within(members).getByText('Trễ 3 task')).toBeInTheDocument()
    expect(within(members).getByText('Chưa có task đến hạn')).toBeInTheDocument()
    expect(within(members).getAllByText('Tạm tính').length).toBeGreaterThan(0)
    // mỗi khung có data-color nhưng cũng có nhãn chữ → không phụ thuộc màu
    for (const frame of members.querySelectorAll('[data-color]')) expect(frame.textContent?.trim().length).toBeGreaterThan(10)

    await user.click(within(members).getByRole('button', { name: /Bình/ }))
    const sheet = await screen.findByRole('dialog', { name: 'Task của Bình' })
    expect(await within(sheet).findByRole('link', { name: 'Viết báo cáo' })).toHaveAttribute('href', `/groups/${GROUP_ID}/tasks/task-9`)
    expect(within(sheet).getByText('Quá hạn, chưa có kết quả')).toBeInTheDocument()
    expect(api.calls.some((c) => c.path === `/groups/${GROUP_ID}/dashboard/members/${BINH.userId}`)).toBe(true)
  })

  it('empty/edge: nhóm chưa có task, chưa nhận task, không có risk/hỗ trợ; kỳ chưa có task báo cáo thì không mở form rỗng', async () => {
    const data = dashboard(GroupRole.MEMBER, {
      myActions: [],
      myTasks: [],
      sharedRisks: [],
      openHelp: [],
      flags: { groupHasAnyTask: true, iHaveAnyTask: false },
      nextCheckpoint: { ...dashboard().nextCheckpoint!, myRequiredTaskCount: 0, canReportNow: false },
      myPoints: { policyActive: false, confirmedPoints: null, potentialPoints: null, label: null },
    })
    mockApi([['GET', dash, data], ...shellRoutes(AN, GroupRole.MEMBER)])
    renderApp(`/groups/${GROUP_ID}`)

    expect(await screen.findByText('Bạn chưa có nhiệm vụ được giao.')).toBeInTheDocument()
    expect(screen.getAllByText('Hiện không có việc cần xử lý thêm.').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/Kỳ này bạn chưa có task cần báo cáo/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Cập nhật checkpoint' })).not.toBeInTheDocument()
    expect(screen.getByText('Nhóm chưa có phương án điểm đã duyệt.')).toBeInTheDocument()
  })

  it('điều hướng kỳ qua previousCycleId/nextCycleId; task của tôi có CTA báo khó khăn theo allowedActions', async () => {
    mockApi([['GET', dash, dashboard(GroupRole.MEMBER)], ...shellRoutes(AN, GroupRole.MEMBER)])
    renderApp(`/groups/${GROUP_ID}`)
    expect(await screen.findByRole('link', { name: 'Kỳ trước' })).toHaveAttribute('href', `/groups/${GROUP_ID}?cycleId=cyc-2`)
    expect(screen.getByRole('link', { name: 'Kỳ sau' })).toHaveAttribute('href', `/groups/${GROUP_ID}?cycleId=cyc-4`)
    const tasks = screen.getByRole('heading', { name: 'Công việc của tôi' }).closest('section') as HTMLElement
    expect(within(tasks).getByText('Thực hiện: Đã nộp — chờ TL duyệt')).toBeInTheDocument()
    expect(within(tasks).getByText('Kế hoạch: Đã duyệt')).toBeInTheDocument()
    expect(within(tasks).getByText('Nộp đúng hạn — chờ TL duyệt')).toBeInTheDocument()
    expect(within(tasks).getByRole('link', { name: 'Báo khó khăn' })).toHaveAttribute('href', `/groups/${GROUP_ID}/tasks/task-1/difficulty`)
  })
})

describe('S03 — Tổng quan nhóm (TL)', () => {
  const leadPanel: LeadPanel = {
    pendingPlans: {
      count: 1,
      items: [{ planId: 'plan-2', taskId: 'task-1', taskTitle: 'Thiết kế API', assignee: AN, submittedAt: '2026-11-17T10:00:00Z', isChange: true }],
    },
    privateSupportRequests: {
      count: 1,
      items: [
        {
          id: 'sr-1',
          taskId: 'task-1',
          taskTitle: 'Thiết kế API',
          sender: BINH,
          obstacleType: 'TECHNICAL',
          status: 'OPEN',
          createdAt: '2026-11-17T10:00:00Z',
          updatedAt: '2026-11-17T10:00:00Z',
        },
      ],
    },
    reviewCycle: {
      cycleId: 'cyc-3',
      phase: 'AWAITING_LEAD_REVIEW',
      asOf: '2026-11-20T15:00:01Z',
      isAtCutoff: true,
      members: { total: 5, reported: 4, notResponded: 1 },
      tasks: { total: 9, accepted: 0, submittedPendingReview: 6, late: 2, needsRework: 0, noResult: 2, carryoverRequired: 0 },
      openRisks: 1,
      openPrivateSupportRequests: 1,
      openHelpRequests: 1,
      notRespondedMembers: [{ userId: 'u-dung', name: 'Dung' }],
      headline: 'Kỳ 3 đã chốt: 4/5 thành viên đã báo cáo, 6 task chờ bạn duyệt, 2 task chưa có kết quả.',
    },
    awaitingReviewCount: 6,
    carryoverRequiredCount: 0,
  }
  const queue: ReviewQueueResponse = {
    cycle: cycle(3),
    serverTime: '2026-11-20T15:05:00Z',
    canReviewNow: true,
    awaitingReview: [
      {
        row: {
          taskId: 'task-1',
          roundId: 'round-1',
          roundNo: 1,
          title: 'Thiết kế API',
          assignee: AN,
          handedOverTo: null,
          deadline: '2026-11-20T13:00:00Z',
          difficulty: 'HARD',
          taskStatus: 'SUBMITTED',
          timeliness: 'ON_TIME_PENDING_REVIEW',
          timelinessLabel: 'Nộp đúng hạn — chờ TL duyệt',
          isLate: false,
          submittedAt: '2026-11-20T12:30:00Z',
          review: null,
          completedAt: null,
          acceptedAt: null,
          completedLate: false,
          reported: true,
          declaredResult: 'RESULT_SUBMITTED',
          riskLevel: 'ON_TRACK',
          reportNote: null,
          carryover: { required: false, reason: null, targetRoundId: null },
        },
        submission: null,
        canReviewNow: true,
      },
    ],
    carryoverRequired: [],
    reviewed: [],
  }

  it('TL có hàng đợi kế hoạch chờ duyệt, đề nghị hỗ trợ riêng và hàng đợi duyệt kết quả; Dung hiện "Chưa có cập nhật" thay vì trễ', async () => {
    mockApi([
      ['GET', dash, dashboard(GroupRole.LEAD, { leadPanel })],
      ['GET', `/groups/${GROUP_ID}/checkpoint-cycles/cyc-3/review-queue`, queue],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    renderApp(`/groups/${GROUP_ID}`)

    const lead = (await screen.findByRole('heading', { name: 'Dành cho trưởng nhóm' })).closest('section') as HTMLElement
    expect(within(lead).getByRole('link', { name: 'Thiết kế API' })).toHaveAttribute('href', `/groups/${GROUP_ID}/tasks/task-1?planId=plan-2`)
    expect(within(lead).getByText('Đề xuất thay đổi')).toBeInTheDocument()
    expect(within(lead).getByRole('link', { name: 'Bình · task Thiết kế API' })).toHaveAttribute('href', `/groups/${GROUP_ID}/difficulty/sr-1`)
    expect(within(lead).getByText(/Kỳ 3 đã chốt: 4\/5 thành viên đã báo cáo/)).toBeInTheDocument()
    expect(within(lead).getByText('Chưa có cập nhật: Dung')).toBeInTheDocument()
    expect(await within(lead).findByText('Chờ bạn duyệt')).toBeInTheDocument()
    expect(within(lead).getAllByRole('link', { name: 'Thiết kế API' }).length).toBeGreaterThanOrEqual(2)
  })

  it('nhóm chưa có task: TL có CTA "Lập kế hoạch đầu tiên" tới S04', async () => {
    mockApi([
      ['GET', dash, dashboard(GroupRole.LEAD, { myTasks: [], flags: { groupHasAnyTask: false, iHaveAnyTask: false } })],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    renderApp(`/groups/${GROUP_ID}`)
    const link = await screen.findByRole('link', { name: 'Lập kế hoạch đầu tiên' })
    expect(link).toHaveAttribute('href', `/groups/${GROUP_ID}/tasks`)
    expect(taskSummary().cycle).toEqual(CYCLE3_REF)
  })
})
