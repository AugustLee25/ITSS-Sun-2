/**
 * Dữ liệu mẫu cho test FE, lấy theo `docs/API_CONTRACT.md` §10.2:
 * nhóm "Bài tập lớn CSDL", 5 người (Minh LEAD; An, Bình, Chi, Dung MEMBER), Kỳ 3 = (13/11 22:00, 20/11 22:00] giờ VN,
 * task Khó của An (deadline 20:00 VN = 13:00Z, nộp 19:30 VN = 12:30Z), điểm 10,0000 = 100000 units.
 * Khai báo bằng kiểu của `api/types.ts` nên lệch hợp đồng sẽ làm test không biên dịch.
 */
import {
  DashboardColor,
  DashboardReasonCode,
  Difficulty,
  GroupHealth,
  GroupRole,
  MemberReportState,
  PlanApprovalSource,
  PlanStatus,
  PointTaskStatus,
  RiskStatus,
  RoundReason,
  RoundState,
  TaskAction,
  TaskStatus,
  TaskTimeliness,
  type CheckpointCycle,
  type CheckpointSchedule,
  type CycleRef,
  type DashboardResponse,
  type Group,
  type GroupCard,
  type Invite,
  type Member,
  type MemberProgress,
  type PlanRevision,
  type TaskDetail,
  type TaskRound,
  type TaskSubmission,
  type TaskSummary,
  type UserProfile,
  type UserRef,
} from '@/api/types'

export const TZ = 'Asia/Ho_Chi_Minh'

export const MINH: UserRef = { userId: 'u-minh', name: 'Minh' }
export const AN: UserRef = { userId: 'u-an', name: 'An' }
export const BINH: UserRef = { userId: 'u-binh', name: 'Bình' }
export const CHI: UserRef = { userId: 'u-chi', name: 'Chi' }
export const DUNG: UserRef = { userId: 'u-dung', name: 'Dung' }

export const profileOf = (user: UserRef): UserProfile => ({
  ...user,
  email: `${user.name.toLowerCase()}@example.com`,
  createdAt: '2026-11-01T00:00:00Z',
})

export const GROUP_ID = 'grp-1'

export const groupFor = (role: GroupRole): Group => ({
  id: GROUP_ID,
  name: 'Bài tập lớn CSDL',
  courseName: 'Cơ sở dữ liệu',
  startDate: '2026-11-01',
  deadline: '2026-12-20',
  timezone: TZ,
  memberCount: 5,
  leader: MINH,
  myRole: role,
  createdAt: '2026-11-01T00:00:00Z',
  version: 1,
})

export const groupCard = (overrides: Partial<GroupCard> = {}): GroupCard => ({
  id: GROUP_ID,
  name: 'Bài tập lớn CSDL',
  courseName: 'Cơ sở dữ liệu',
  memberCount: 5,
  deadline: '2026-12-20',
  myRole: GroupRole.MEMBER,
  attention: { count: 1, label: '1 checkpoint cần phản hồi' },
  unreadNotifications: 3,
  lastActivityAt: '2026-11-15T09:12:00Z',
  ...overrides,
})

export const MEMBERS: Member[] = [
  { ...MINH, role: GroupRole.LEAD, joinedAt: '2026-11-01T10:00:00Z' },
  { ...AN, role: GroupRole.MEMBER, joinedAt: '2026-11-01T18:30:00Z' },
  { ...BINH, role: GroupRole.MEMBER, joinedAt: '2026-11-02T08:00:00Z' },
  { ...CHI, role: GroupRole.MEMBER, joinedAt: '2026-11-02T09:00:00Z' },
  { ...DUNG, role: GroupRole.MEMBER, joinedAt: '2026-11-02T10:00:00Z' },
]

export const CYCLE3_REF: CycleRef = {
  id: 'cyc-3',
  sequenceNo: 3,
  periodStartAt: '2026-11-13T15:00:00Z',
  closesAt: '2026-11-20T15:00:00Z',
  label: 'Kỳ 3 · 13/11 22:00 → 20/11 22:00',
}

export const cycle = (no: number, overrides: Partial<CheckpointCycle> = {}): CheckpointCycle => {
  const closes = Date.parse('2026-11-20T15:00:00Z') + (no - 3) * 7 * 24 * 3600_000
  const iso = (ms: number) => new Date(ms).toISOString().replace('.000Z', 'Z')
  return {
    id: `cyc-${no}`,
    groupId: GROUP_ID,
    scheduleId: 'sch-1',
    sequenceNo: no,
    periodStartAt: iso(closes - 7 * 24 * 3600_000),
    opensAt: iso(closes - 7 * 24 * 3600_000),
    reminderAt: iso(closes - 7 * 3600_000),
    closesAt: iso(closes),
    status: no < 3 ? 'CLOSED' : no === 3 ? 'OPEN' : 'SCHEDULED',
    phase: 'TENTATIVE',
    phaseLabel: 'Tạm tính',
    finalizedAt: null,
    isCurrent: no === 3,
    reportWindowOpen: no === 3,
    timezone: TZ,
    label: `Kỳ ${no} · ${no}`,
    ...overrides,
  }
}

export const SCHEDULE: CheckpointSchedule = {
  id: 'sch-1',
  versionNo: 1,
  weekday: 5,
  cutoffTime: '22:00',
  timezone: TZ,
  reminderBeforeMinutes: 420,
  reportingWindowMinutes: null,
  effectiveFrom: '2026-10-31T17:00:00Z',
  agreedAt: '2026-11-01T10:00:00Z',
  recordedBy: MINH,
  isCurrent: true,
  summaryLabel: 'Chốt thứ Sáu 22:00 → nhắc thứ Sáu 15:00',
}

export const INVITE: Invite = {
  code: 'K7P4Q9XD',
  status: 'ACTIVE',
  sharePath: '/join/K7P4Q9XD',
  createdBy: MINH,
  createdAt: '2026-11-15T10:00:00Z',
  expiresAt: '2026-11-29T10:00:00Z',
}

// ───────────── Task ─────────────

export const taskSummary = (overrides: Partial<TaskSummary> = {}): TaskSummary => ({
  id: 'task-1',
  groupId: GROUP_ID,
  title: 'Thiết kế API',
  difficulty: Difficulty.HARD,
  originalAssignee: AN,
  currentExecutor: AN,
  cycle: CYCLE3_REF,
  roundId: 'round-1',
  roundNo: 1,
  startAt: '2026-11-14T01:00:00Z',
  deadline: '2026-11-20T13:00:00Z',
  originalDeadline: '2026-11-20T13:00:00Z',
  committed: true,
  planStatus: PlanStatus.APPROVED,
  pendingRevisionStatus: null,
  pendingRevisionId: null,
  taskStatus: TaskStatus.SUBMITTED,
  riskStatus: RiskStatus.NORMAL,
  timeliness: TaskTimeliness.ON_TIME_PENDING_REVIEW,
  timelinessLabel: 'Nộp đúng hạn — chờ TL duyệt',
  isLate: false,
  needsCarryover: false,
  carryoverReason: null,
  latestSubmittedAt: '2026-11-20T12:30:00Z',
  points: {
    amount: { pointUnits: 100000, percentLabel: '10,0000' },
    status: PointTaskStatus.AWAITING_REVIEW,
    originalAssignee: AN,
    currentRecipient: AN,
    heldForHelpRequest: false,
    awarded: false,
  },
  hasOpenHelpRequest: false,
  version: 5,
  allowedActions: [TaskAction.VIEW_RESULT, TaskAction.REPORT_CHECKPOINT, TaskAction.REPORT_DIFFICULTY],
  ...overrides,
})

export const effectivePlan = (overrides: Partial<PlanRevision> = {}): PlanRevision => ({
  id: 'plan-1',
  taskId: 'task-1',
  roundId: 'round-1',
  revisionNo: 1,
  baseRevisionId: null,
  status: PlanStatus.APPROVED,
  proposedCycle: CYCLE3_REF,
  assignee: AN,
  title: 'Thiết kế API',
  description: 'API cho module nhóm và task',
  expectedOutput: 'OpenAPI + 12 endpoint chạy được',
  startAt: '2026-11-14T01:00:00Z',
  deadline: '2026-11-20T13:00:00Z',
  difficulty: Difficulty.HARD,
  approvalSource: PlanApprovalSource.MEMBER_PLAN,
  changeReason: null,
  submittedBy: AN,
  submittedAt: '2026-11-14T00:30:00Z',
  reviewedBy: MINH,
  reviewedAt: '2026-11-14T02:00:00Z',
  reviewNote: null,
  changes: [],
  isEffective: true,
  createdAt: '2026-11-14T00:00:00Z',
  updatedAt: '2026-11-14T02:00:00Z',
  version: 3,
  ...overrides,
})

export const round1 = (overrides: Partial<TaskRound> = {}): TaskRound => ({
  id: 'round-1',
  taskId: 'task-1',
  roundNo: 1,
  reason: RoundReason.INITIAL,
  state: RoundState.AWAITING_REVIEW,
  cycle: CYCLE3_REF,
  previousRoundId: null,
  effectivePlanId: 'plan-1',
  deadline: '2026-11-20T13:00:00Z',
  decision: null,
  startedAt: '2026-11-14T02:00:00Z',
  closedAt: null,
  carryover: null,
  ...overrides,
})

export const submission1: TaskSubmission = {
  id: 'sub-1',
  roundId: 'round-1',
  taskId: 'task-1',
  planRevisionId: 'plan-1',
  submissionNo: 1,
  submittedBy: AN,
  resultNote: 'Đã xong 12 endpoint',
  evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/7', label: 'PR #7' }],
  submittedAt: '2026-11-20T12:30:00Z',
  deadline: '2026-11-20T13:00:00Z',
  isOnTime: true,
  isReviewTarget: true,
  review: null,
  supportSubmissionId: null,
}

export const taskDetail = (overrides: Partial<TaskDetail> = {}): TaskDetail => ({
  ...taskSummary(),
  description: 'API cho module nhóm và task',
  expectedOutput: 'OpenAPI + 12 endpoint chạy được',
  effectivePlan: effectivePlan(),
  pendingRevision: null,
  currentRound: round1(),
  rounds: [round1()],
  submissions: [submission1],
  acceptedSubmissionId: null,
  completedAt: null,
  acceptedAt: null,
  completedLate: false,
  independentReviewer: null,
  lastProgress: null,
  helpRequestId: null,
  checkpoint: { cycle: CYCLE3_REF, myReportState: MemberReportState.NOT_RESPONDED, reportWindowOpen: true },
  ...overrides,
})

// ───────────── Dashboard ─────────────

const frame = (
  user: UserRef,
  color: DashboardColor,
  statusLabel: string,
  reasonCode: DashboardReasonCode,
  reason: string,
  counts: Partial<MemberProgress['evaluation']['counts']> = {},
): MemberProgress => ({
  member: user,
  role: user.userId === MINH.userId ? GroupRole.LEAD : GroupRole.MEMBER,
  evaluation: {
    color,
    colorLabel: { GREEN: 'Xanh', YELLOW: 'Vàng', RED: 'Đỏ', GRAY: 'Xám' }[color],
    statusLabel,
    reasonCode,
    reason,
    provisional: color === 'GREEN',
    phase: 'TENTATIVE',
    phaseLabel: 'Tạm tính',
    counts: {
      totalTasks: 3,
      dueTasks: 3,
      acceptedTasks: 0,
      pendingReviewTasks: 0,
      lateTasks: 0,
      needsReworkTasks: 0,
      noResultTasks: 0,
      reportedTasks: 3,
      missingReportTasks: 0,
      ...counts,
    },
  },
  cutoffEvaluation: null,
  reportState: MemberReportState.SUBMITTED,
  reportLabel: 'Đã gửi đầy đủ',
  reportSubmittedAt: '2026-11-17T14:00:00Z',
  dropoutRisk: null,
})

export const dashboard = (role: GroupRole = GroupRole.MEMBER, overrides: Partial<DashboardResponse> = {}): DashboardResponse => ({
  serverTime: '2026-11-18T03:00:00Z',
  group: {
    id: GROUP_ID,
    name: 'Bài tập lớn CSDL',
    courseName: 'Cơ sở dữ liệu',
    deadline: '2026-12-20',
    memberCount: 5,
    leader: MINH,
    health: GroupHealth.NEEDS_ATTENTION,
    healthLabel: 'Cần xem xét',
    healthReason: '1 risk đang mở ảnh hưởng tiến độ; chưa phải kết luận về năng suất cá nhân.',
  },
  myRole: role,
  cycle: cycle(3, { label: CYCLE3_REF.label }),
  previousCycleId: 'cyc-2',
  nextCycleId: 'cyc-4',
  myActions: [
    {
      kind: 'CHECKPOINT_REPORT',
      title: 'Cập nhật checkpoint Kỳ 3',
      subtitle: 'Chốt 22:00 Thứ Sáu 20/11',
      dueAt: '2026-11-20T15:00:00Z',
      count: 3,
      target: { screen: 'S06', params: { groupId: GROUP_ID, cycleId: 'cyc-3' } },
    },
  ],
  myTasks: [taskSummary({ allowedActions: [TaskAction.REPORT_CHECKPOINT, TaskAction.REPORT_DIFFICULTY] })],
  nextCheckpoint: {
    cycle: cycle(3, { label: CYCLE3_REF.label }),
    myRequiredTaskCount: 3,
    myRespondedTaskCount: 0,
    myReportState: MemberReportState.NOT_RESPONDED,
    myReportLabel: 'Chưa có cập nhật',
    canReportNow: true,
    reportedMemberCount: 2,
    notRespondedMemberCount: 3,
  },
  memberProgress: [
    frame(AN, DashboardColor.GREEN, 'Đã nộp đủ — chờ TL duyệt', DashboardReasonCode.ALL_SUBMITTED_PENDING_REVIEW, '3/3 task đến hạn đã nộp đúng hạn, đang chờ TL duyệt.', {
      pendingReviewTasks: 3,
    }),
    frame(BINH, DashboardColor.YELLOW, 'Trễ 2 task', DashboardReasonCode.LATE_1_2, '2 task trễ deadline.', { lateTasks: 2 }),
    frame(CHI, DashboardColor.RED, 'Trễ 3 task', DashboardReasonCode.LATE_3_PLUS, '3 task trễ deadline.', { lateTasks: 3 }),
    frame(DUNG, DashboardColor.GRAY, 'Chưa có task đến hạn', DashboardReasonCode.NO_TASK_DUE, 'Kỳ này chưa có task đến hạn.', { dueTasks: 0 }),
  ],
  leadPanel: null,
  sharedRisks: [
    {
      id: 'risk-1',
      riskType: 'DELAY_RISK',
      taskId: 'task-1',
      taskTitle: 'Thiết kế API',
      publicSummary: 'Phần API có thể cần thêm 1 ngày',
      createdAt: '2026-11-17T09:00:00Z',
    },
  ],
  openHelp: [],
  myPoints: {
    policyActive: true,
    confirmedPoints: { pointUnits: 0, percentLabel: '0,0000' },
    potentialPoints: { pointUnits: 200000, percentLabel: '20,0000' },
    label: 'Đã xác nhận 0,0000% / Có thể đạt 20,0000%',
  },
  recentActivity: [{ id: 'act-1', at: '2026-11-17T14:00:00Z', text: 'An đã gửi báo cáo checkpoint', target: null }],
  flags: { groupHasAnyTask: true, iHaveAnyTask: true },
  ...overrides,
})
