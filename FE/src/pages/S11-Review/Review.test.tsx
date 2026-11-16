import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type SupportWork } from '@/api/types'
import { BINH, GROUP_ID, MINH } from '@/test/fixtures'
import { apiError, mockApi, renderApp, shellRoutes, type ApiRoute } from '@/test/renderApp'
import { HELP_ID, REVIEW_PAGE, WORK_ID, WORK_PAGE, reviewPreview, submittedWork, supportWork } from '../S10-HelpExecution/workFixtures'
import type { ReviewPreviewResponse } from '@/api/types'

afterEach(() => vi.unstubAllGlobals())

const routes = (preview: ReviewPreviewResponse, work: SupportWork = submittedWork(), extra: ApiRoute[] = [], role: GroupRole = GroupRole.LEAD): ApiRoute[] => [
  ['GET', `/help-requests/${HELP_ID}`, { work: { id: WORK_ID } }],
  ['GET', `/support-works/${WORK_ID}`, work],
  ['GET', '/task-submissions/sub-1/review-preview', preview],
  ...extra,
  ...shellRoutes(role === GroupRole.LEAD ? MINH : BINH, role),
]

const reviewResponse = (decision: string) => ({
  review: { id: 'rv-1', submissionId: 'sub-1', decision, reviewer: MINH, issueDescription: null, reviewedAt: '2026-11-27T16:00:00Z' },
  submission: {},
  task: {},
  award:
    decision === 'ACCEPTED'
      ? {
          ledgerEntryId: 'led-1',
          settlementId: 'set-1',
          recipient: BINH,
          taskId: 'task-1',
          points: { pointUnits: 100000, percentLabel: '+10,0000' },
          confirmedBefore: { pointUnits: 200000, percentLabel: '20,0000' },
          confirmedAfter: { pointUnits: 300000, percentLabel: '30,0000' },
          source: 'TAKEN_OVER',
        }
      : null,
  supportWork: null,
})

describe('S11 — TL xác nhận kết quả & ghi điểm', () => {
  it('xem đúng bản nộp + điểm sẽ ghi do server tính; Chấp nhận gửi Idempotency-Key một lần rồi sang S12 đúng khoản', async () => {
    const api = mockApi(routes(reviewPreview(), submittedWork(), [['POST', '/task-submissions/sub-1/review', () => reviewResponse('ACCEPTED')]]))
    const user = userEvent.setup()
    const { router } = renderApp(REVIEW_PAGE)

    expect(await screen.findByRole('heading', { level: 1, name: 'Xác nhận kết quả & Ghi điểm' })).toBeInTheDocument()
    const result = (await screen.findByRole('heading', { name: 'Kết quả' })).closest('section') as HTMLElement
    expect(within(result).getByText('Nộp đúng hạn')).toBeInTheDocument()
    expect(within(result).getByRole('link', { name: 'PR #9' })).toHaveAttribute('href', 'https://github.com/x/y/pull/9')
    expect(within(result).getByText(/Thời gian tự khai: 1 giờ 35 phút/)).toBeInTheDocument()
    const award = screen.getByRole('heading', { name: 'Điểm sẽ ghi' }).closest('section') as HTMLElement
    expect(within(award).getByText('+10,0000 điểm % cho Bình từ task Thiết kế API của An')).toBeInTheDocument()
    expect(within(award).getByText('Đã xác nhận hiện tại: 20,0000 điểm %')).toBeInTheDocument()
    expect(within(award).getByText('Sau khi duyệt: 30,0000 điểm %')).toBeInTheDocument()
    expect(within(award).getByText('Điểm chỉ được ghi sau khi kết quả được chấp nhận')).toBeInTheDocument()

    await user.dblClick(screen.getByRole('button', { name: 'Chấp nhận kết quả & ghi điểm' }))
    await waitFor(() => expect(router.state.location.search).toBe('?tab=history&ledgerEntryId=led-1'))
    const writes = api.writes('POST', '/task-submissions/sub-1/review')
    expect(writes).toHaveLength(1)
    expect(writes[0]?.body).toEqual({ decision: 'ACCEPTED', issueDescription: null })
    expect(writes[0]?.headers.get('Idempotency-Key')).toBeTruthy()
  })

  it('Cần sửa bắt buộc mô tả (chưa gửi khi trống), không ghi điểm và quay về S10', async () => {
    const api = mockApi(routes(reviewPreview(), submittedWork(), [['POST', '/task-submissions/sub-1/review', () => reviewResponse('FIX_REQUIRED')]]))
    const user = userEvent.setup()
    const { router } = renderApp(REVIEW_PAGE)
    await user.click(await screen.findByRole('radio', { name: 'Cần sửa' }))
    await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu sửa' }))
    expect(await screen.findByText('Mô tả cần từ 10 đến 2000 ký tự')).toBeInTheDocument()
    expect(api.writes('POST', /review/)).toHaveLength(0)

    await user.type(screen.getByLabelText(/Mô tả lỗi hoặc điều cần bổ sung/), 'Thiếu OpenAPI cho nhóm endpoint task')
    await user.click(screen.getByRole('button', { name: 'Gửi yêu cầu sửa' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(WORK_PAGE))
    expect(api.writes('POST', /review/)[0]?.body).toEqual({ decision: 'FIX_REQUIRED', issueDescription: 'Thiếu OpenAPI cho nhóm endpoint task' })
  })

  it('người không có quyền: thấy lý do, người duyệt hợp lệ và quy tắc không ai tự duyệt điểm mình nhận; không có nút duyệt', async () => {
    mockApi(
      routes(
        reviewPreview({
          canReview: false,
          cannotReviewReason: { code: 'SELF_ACTION_FORBIDDEN', message: 'Bạn là người nhận điểm nên không thể tự duyệt.' },
          authorizedReviewer: MINH,
        }),
        submittedWork(),
        [],
        GroupRole.MEMBER,
      ),
    )
    renderApp(REVIEW_PAGE)
    expect(await screen.findByText('Bạn là người nhận điểm nên không thể tự duyệt.')).toBeInTheDocument()
    expect(screen.getByText('Người có quyền duyệt: Minh')).toBeInTheDocument()
    expect(screen.getByText(/Không ai được tự duyệt điểm mình nhận/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Chấp nhận kết quả & ghi điểm/ })).not.toBeInTheDocument()
  })

  it('409 STALE_SUBMISSION: giải thích và cho tải lại bản mới, không báo đã ghi điểm', async () => {
    mockApi(routes(reviewPreview(), submittedWork(), [['POST', '/task-submissions/sub-1/review', () => apiError(409, 'STALE_SUBMISSION', 'Có bản nộp mới hơn.')]]))
    const user = userEvent.setup()
    renderApp(REVIEW_PAGE)
    await user.click(await screen.findByRole('button', { name: 'Chấp nhận kết quả & ghi điểm' }))
    expect(await screen.findByText(/vừa thay đổi/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tải lại' })).toBeInTheDocument()
    expect(screen.queryByText(/Đã ghi nhận/)).not.toBeInTheDocument()
  })

  it('chưa có bản nộp → trạng thái rỗng dẫn về S10', async () => {
    mockApi(routes(reviewPreview(), supportWork({ latestTaskSubmissionId: null })))
    renderApp(REVIEW_PAGE)
    expect(await screen.findByText('Chưa có kết quả để xác nhận')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Về phần việc' })).toHaveAttribute('href', `/groups/${GROUP_ID}/help/${HELP_ID}/work`)
  })
})
