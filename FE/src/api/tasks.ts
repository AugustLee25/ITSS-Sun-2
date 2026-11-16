import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api } from './client'
import { dashboardKeyPrefix } from './dashboard'
import { useIdempotentMutation } from './idempotentMutation'
import type {
  CarryoverRequest,
  CarryoverResponse,
  CreateTaskRequest,
  ItemsResponse,
  ListTasksQuery,
  Page,
  PlanMutationResponse,
  PointPolicyPreviewResponse,
  PointPolicyResponse,
  ProgressNote,
  ProgressUpdateRequest,
  ProposeChangeRequest,
  ReviewPlanRequest,
  ReviewPlanResponse,
  ReviewPreviewResponse,
  ReviewQueueResponse,
  ReviewResultRequest,
  ReviewResultResponse,
  SetIndependentReviewerRequest,
  SubmitResultRequest,
  SubmitResultResponse,
  TaskDetail,
  TaskHistoryEntry,
  TaskSubmission,
  TaskSummary,
  UpdatePlanRequest,
  VersionedRequest,
} from './types'

const enc = encodeURIComponent

export const taskKeys = {
  all: ['tasks'] as const,
  lists: (groupId: string) => ['tasks', 'list', groupId] as const,
  list: (groupId: string, query: ListTasksQuery) => ['tasks', 'list', groupId, query] as const,
  detail: (taskId: string) => ['tasks', 'detail', taskId] as const,
  history: (taskId: string, page: number) => ['tasks', 'history', taskId, page] as const,
  historyAll: (taskId: string) => ['tasks', 'history', taskId] as const,
  roundSubmissions: (roundId: string) => ['tasks', 'round-submissions', roundId] as const,
  reviewPreview: (submissionId: string) => ['tasks', 'review-preview', submissionId] as const,
  reviewQueue: (groupId: string, cycleId: string) => ['tasks', 'review-queue', groupId, cycleId] as const,
  reviewQueues: (groupId: string) => ['tasks', 'review-queue', groupId] as const,
  pointPolicy: (groupId: string) => ['points', 'policy', groupId] as const,
  pointPreview: (groupId: string) => ['points', 'policy-preview', groupId] as const,
}

/** Sau mọi thao tác ghi vào task: làm mới chi tiết, danh sách, hàng đợi duyệt và bảng tổng quan của nhóm. */
export function invalidateTaskData(queryClient: QueryClient, groupId: string, taskId?: string) {
  if (taskId) void queryClient.invalidateQueries({ queryKey: taskKeys.detail(taskId) })
  if (taskId) void queryClient.invalidateQueries({ queryKey: taskKeys.historyAll(taskId) })
  void queryClient.invalidateQueries({ queryKey: taskKeys.lists(groupId) })
  void queryClient.invalidateQueries({ queryKey: taskKeys.reviewQueues(groupId) })
  void queryClient.invalidateQueries({ queryKey: dashboardKeyPrefix(groupId) })
  void queryClient.invalidateQueries({ queryKey: ['notifications'] })
}

// ───────────────────────────── Đọc ─────────────────────────────

/** S04: `GET /groups/{groupId}/tasks` (phân trang, lọc theo tab/kỳ/người/trạng thái/độ khó/deadline). */
export function useTasks(groupId: string, query: ListTasksQuery) {
  return useQuery({
    queryKey: taskKeys.list(groupId, query),
    queryFn: ({ signal }) => api.get<Page<TaskSummary>>(`/groups/${enc(groupId)}/tasks`, { signal, query: { ...query } }),
    enabled: !!groupId,
    staleTime: 15_000,
  })
}

/** S05: chi tiết task (phiên bản kế hoạch, vòng, lần nộp, `allowedActions`). */
export function useTask(taskId: string) {
  return useQuery({
    queryKey: taskKeys.detail(taskId),
    queryFn: ({ signal }) => api.get<TaskDetail>(`/tasks/${enc(taskId)}`, { signal }),
    enabled: !!taskId,
    staleTime: 10_000,
  })
}

export function useTaskHistory(taskId: string, page: number, enabled: boolean) {
  return useQuery({
    queryKey: taskKeys.history(taskId, page),
    queryFn: ({ signal }) => api.get<Page<TaskHistoryEntry>>(`/tasks/${enc(taskId)}/history`, { signal, query: { page, size: 20 } }),
    enabled: enabled && !!taskId,
  })
}

/** Các lần nộp của một vòng (xem vòng cũ). */
export function useRoundSubmissions(roundId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: taskKeys.roundSubmissions(roundId ?? ''),
    queryFn: async ({ signal }) =>
      (await api.get<ItemsResponse<TaskSubmission>>(`/task-rounds/${enc(roundId ?? '')}/submissions`, { signal })).items,
    enabled: enabled && !!roundId,
  })
}

/** Xem trước "Điểm sẽ ghi" và quyền duyệt (`canReview`, `cannotReviewReason`) của một bản nộp. */
export function useReviewPreview(submissionId: string | null, enabled = true) {
  return useQuery({
    queryKey: taskKeys.reviewPreview(submissionId ?? ''),
    queryFn: ({ signal }) => api.get<ReviewPreviewResponse>(`/task-submissions/${enc(submissionId ?? '')}/review-preview`, { signal }),
    enabled: enabled && !!submissionId,
    retry: false,
  })
}

/** S03 (TL): hàng đợi duyệt sau checkpoint của một kỳ. */
export function useReviewQueue(groupId: string, cycleId: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: taskKeys.reviewQueue(groupId, cycleId ?? ''),
    queryFn: ({ signal }) =>
      api.get<ReviewQueueResponse>(`/groups/${enc(groupId)}/checkpoint-cycles/${enc(cycleId ?? '')}/review-queue`, { signal }),
    enabled: enabled && !!groupId && !!cycleId,
    retry: false,
  })
}

/** S04 (TL): phương án điểm hiện hành và bảng W_i/B_i (Member nhận `allocation = null`). */
export function usePointPolicy(groupId: string, enabled: boolean) {
  return useQuery({
    queryKey: taskKeys.pointPolicy(groupId),
    queryFn: ({ signal }) => api.get<PointPolicyResponse>(`/groups/${enc(groupId)}/point-policy`, { signal }),
    enabled: enabled && !!groupId,
    retry: false,
  })
}

/** S04 (TL): xem phân công có cân đối trước khi khóa điểm — `POST …/point-policy/preview` chỉ đọc, không ghi. */
export function usePointPolicyPreview(groupId: string, enabled: boolean) {
  return useQuery({
    queryKey: taskKeys.pointPreview(groupId),
    queryFn: ({ signal }) => api.post<PointPolicyPreviewResponse>(`/groups/${enc(groupId)}/point-policy/preview`, {}, { signal }),
    enabled: enabled && !!groupId,
    retry: false,
  })
}

// ───────────────────────────── Ghi ─────────────────────────────

/** S04: TL giao task (tạo task + vòng 1 + kế hoạch nháp). */
export function useCreateTask(groupId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<TaskDetail, CreateTaskRequest>({
    mutationFn: (body, idempotencyKey) => api.post<TaskDetail>(`/groups/${enc(groupId)}/tasks`, body, { idempotencyKey }),
    onSuccess: (task) => invalidateTaskData(queryClient, groupId, task.id),
  })
}

/** S05: sửa bản nháp/bản bị trả lại (`Lưu nháp`). Không đổi trạng thái. */
export function useUpdatePlan(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ planId, ...body }: UpdatePlanRequest & { planId: string }) =>
      api.patch<PlanMutationResponse>(`/plans/${enc(planId)}`, body),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05: `Gửi TL duyệt`. */
export function useSubmitPlan(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<PlanMutationResponse, VersionedRequest & { planId: string }>({
    mutationFn: ({ planId, ...body }, idempotencyKey) =>
      api.post<PlanMutationResponse>(`/plans/${enc(planId)}/submit`, body, { idempotencyKey }),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05: rút kế hoạch đang chờ duyệt về nháp. */
export function useWithdrawPlan(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ planId, ...body }: VersionedRequest & { planId: string }) =>
      api.post<PlanMutationResponse>(`/plans/${enc(planId)}/withdraw`, body),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05 (TL): `Duyệt kế hoạch` / `Yêu cầu chỉnh sửa` — Idempotency-Key BẮT BUỘC. */
export function useReviewPlan(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<ReviewPlanResponse, ReviewPlanRequest & { planId: string }>({
    mutationFn: ({ planId, ...body }, idempotencyKey) =>
      api.post<ReviewPlanResponse>(`/plans/${enc(planId)}/review`, body, { idempotencyKey }),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05: `Đề xuất thay đổi` kế hoạch đã duyệt (bản cũ vẫn hiệu lực). */
export function useProposeChange(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<PlanMutationResponse, ProposeChangeRequest>({
    mutationFn: (body, idempotencyKey) =>
      api.post<PlanMutationResponse>(`/tasks/${enc(taskId)}/plan-revisions`, body, { idempotencyKey }),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

export function useStartTask(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: VersionedRequest) => api.post<TaskSummary>(`/tasks/${enc(taskId)}/start`, body),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05: ghi một dòng tiến độ (không đổi deadline/cam kết). */
export function useProgressUpdate(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ProgressUpdateRequest) => api.post<ProgressNote>(`/tasks/${enc(taskId)}/progress`, body),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05: nộp minh chứng URL cho vòng hiện tại — Idempotency-Key BẮT BUỘC. */
export function useSubmitResult(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<SubmitResultResponse, SubmitResultRequest & { roundId: string }>({
    mutationFn: ({ roundId, ...body }, idempotencyKey) =>
      api.post<SubmitResultResponse>(`/task-rounds/${enc(roundId)}/submissions`, body, { idempotencyKey }),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05 (TL/người duyệt): `Chấp nhận` / `Cần sửa` / `Cần cập nhật` — Idempotency-Key BẮT BUỘC. */
export function useReviewResult(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<ReviewResultResponse, ReviewResultRequest & { submissionId: string }>({
    mutationFn: ({ submissionId, ...body }, idempotencyKey) =>
      api.post<ReviewResultResponse>(`/task-submissions/${enc(submissionId)}/review`, body, { idempotencyKey }),
    onSuccess: () => {
      invalidateTaskData(queryClient, groupId, taskId)
      void queryClient.invalidateQueries({ queryKey: ['points'] })
    },
  })
}

/** S05 (TL): `Chốt deadline xử lý tiếp` — Idempotency-Key BẮT BUỘC. */
export function useCarryover(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<CarryoverResponse, CarryoverRequest & { roundId: string }>({
    mutationFn: ({ roundId, ...body }, idempotencyKey) =>
      api.post<CarryoverResponse>(`/task-rounds/${enc(roundId)}/carryover`, body, { idempotencyKey }),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}

/** S05 (TL, task của chính TL có điểm): chỉ định/bỏ người duyệt độc lập. */
export function useSetIndependentReviewer(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: SetIndependentReviewerRequest) => api.put<TaskDetail>(`/tasks/${enc(taskId)}/independent-reviewer`, body),
    onSuccess: () => invalidateTaskData(queryClient, groupId, taskId),
  })
}
