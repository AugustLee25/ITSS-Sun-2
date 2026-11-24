/** Dữ liệu mẫu cho test S10/S11 theo `docs/API_CONTRACT.md` §10.2 và mục J/G (Bình làm thay task Khó 10,0000 của An; Minh là TL). */
import {
  ContributionStatus,
  Difficulty,
  HelpRequestStatus,
  SupportTimelineKind,
  SupportWorkAction,
  SupportWorkStatus,
  type ReviewPreviewResponse,
  type SupportWork,
  type TaskSubmission,
} from '@/api/types'
import { AN, BINH, CYCLE3_REF, GROUP_ID, MINH, taskSummary } from '@/test/fixtures'

export const WORK_ID = 'work-1'
export const HELP_ID = 'help-1'
export const WORK_PAGE = `/groups/${GROUP_ID}/help/${HELP_ID}/work`
export const REVIEW_PAGE = `/groups/${GROUP_ID}/help/${HELP_ID}/review`

export const supportWork = (overrides: Partial<SupportWork> = {}): SupportWork => ({
  id: WORK_ID,
  helpRequestId: HELP_ID,
  title: 'Thiết kế API (toàn bộ task)',
  helper: BINH,
  requester: MINH,
  status: SupportWorkStatus.IN_PROGRESS,
  helpRequestStatus: HelpRequestStatus.IN_PROGRESS,
  statusLabel: 'Đang thực hiện',
  points: { pointUnits: 100000, percentLabel: '10,0000' },
  pointRecorded: false,
  contributionStatus: null,
  updatedAt: '2026-11-21T05:00:00Z',
  groupId: GROUP_ID,
  task: { id: 'task-1', title: 'Thiết kế API' },
  originalAssignee: AN,
  resultApprover: MINH,
  scopeSnapshot: 'Hoàn thiện 12 endpoint nhóm/task, kèm OpenAPI',
  expectedOutput: 'OpenAPI + 12 endpoint chạy được',
  deadlineSnapshot: '2026-11-27T13:00:00Z',
  difficultySnapshot: Difficulty.HARD,
  pointLabel: '+10,0000 điểm % khi TL duyệt (chưa ghi điểm)',
  roundId: 'round-2',
  submissions: [],
  timeline: [{ at: '2026-11-21T05:00:00Z', kind: SupportTimelineKind.OFFER_ACCEPTED, actor: MINH, note: null }],
  contribution: null,
  latestTaskSubmissionId: null,
  cutoffAt: '2026-11-27T15:00:00Z',
  canSubmit: true,
  cannotSubmitReason: null,
  canReviewNow: false,
  successorHelpRequestId: null,
  allowedActions: [SupportWorkAction.SUBMIT_RESULT, SupportWorkAction.REPORT_UNABLE],
  version: 1,
  ...overrides,
})

/** Phần việc đã nộp, chờ TL duyệt. */
export const submittedWork = (overrides: Partial<SupportWork> = {}) =>
  supportWork({
    status: SupportWorkStatus.SUBMITTED,
    statusLabel: 'Đã gửi kết quả',
    contributionStatus: ContributionStatus.PENDING_CONFIRMATION,
    latestTaskSubmissionId: 'sub-1',
    submissions: [
      {
        id: 'ss-1',
        workId: WORK_ID,
        revisionNo: 1,
        resultNote: 'Đã xong 12 endpoint và OpenAPI',
        evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/9', label: 'PR #9' }],
        actualMinutes: 95,
        submittedAt: '2026-11-27T12:30:00Z',
        taskSubmissionId: 'sub-1',
        reviewDecision: null,
        issueDescription: null,
      },
    ],
    allowedActions: [SupportWorkAction.REVIEW_RESULT],
    ...overrides,
  })

export const taskSubmission: TaskSubmission = {
  id: 'sub-1',
  roundId: 'round-2',
  taskId: 'task-1',
  planRevisionId: 'plan-2',
  submissionNo: 1,
  submittedBy: BINH,
  resultNote: 'Đã xong 12 endpoint và OpenAPI',
  evidence: [{ type: 'URL', url: 'https://github.com/x/y/pull/9', label: 'PR #9' }],
  submittedAt: '2026-11-27T12:30:00Z',
  deadline: '2026-11-27T13:00:00Z',
  isOnTime: true,
  isReviewTarget: true,
  review: null,
  supportSubmissionId: 'ss-1',
}

export const reviewPreview = (overrides: Partial<ReviewPreviewResponse> = {}): ReviewPreviewResponse => ({
  submission: taskSubmission,
  task: taskSummary(),
  cycle: CYCLE3_REF,
  cutoffAt: '2026-11-27T15:00:00Z',
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
  ...overrides,
})
