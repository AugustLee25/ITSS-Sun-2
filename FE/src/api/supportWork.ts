import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { dashboardKeyPrefix } from './dashboard'
import { hasStatus } from './errors'
import { useIdempotentMutation } from './idempotentMutation'
import type {
  HelpRequestDetail,
  ReportUnableRequest,
  ResolveDiscussionRequest,
  SubmitSupportResultRequest,
  SubmitSupportResultResponse,
  SupportWork,
} from './types'

const enc = encodeURIComponent

export const supportWorkKeys = {
  all: ['support-work'] as const,
  ref: (key: string) => ['support-work', 'ref', key] as const,
  work: (workId: string) => ['support-work', 'detail', workId] as const,
}

/**
 * Địa chỉ S10/S11 là `/help/:helpRequestId/...`, còn thông báo của server đưa `workId`: chấp nhận cả hai.
 * Tra yêu cầu hỗ trợ trước; 404 thì coi khóa chính là `workId`. `workId = null` ⇒ yêu cầu chưa có người nhận.
 */
export function useSupportWorkRef(key: string) {
  return useQuery({
    queryKey: supportWorkKeys.ref(key),
    queryFn: async ({ signal }) => {
      try {
        const request = await api.get<HelpRequestDetail>(`/help-requests/${enc(key)}`, { signal })
        return { workId: request.work?.id ?? null, request }
      } catch (error) {
        if (hasStatus(error, 404)) return { workId: key, request: null }
        throw error
      }
    },
    enabled: !!key,
  })
}

export function useSupportWork(workId: string | null) {
  return useQuery({
    queryKey: supportWorkKeys.work(workId ?? ''),
    queryFn: ({ signal }) => api.get<SupportWork>(`/support-works/${enc(workId ?? '')}`, { signal }),
    enabled: !!workId,
    staleTime: 10_000,
  })
}

/** Sau mọi thao tác ghi: làm mới phần việc, yêu cầu hỗ trợ, task, điểm, bảng tổng quan, thông báo. */
export function useRefreshSupportData(groupId: string) {
  const queryClient = useQueryClient()
  return () => {
    for (const key of [supportWorkKeys.all, ['help'], ['tasks'], ['points'], ['notifications'], dashboardKeyPrefix(groupId)]) {
      void queryClient.invalidateQueries({ queryKey: key })
    }
  }
}

/** S10 (Helper): `Gửi kết quả` — Idempotency-Key BẮT BUỘC (chống bấm đúp). */
export function useSubmitSupportResult(workId: string, groupId: string) {
  const refresh = useRefreshSupportData(groupId)
  return useIdempotentMutation<SubmitSupportResultResponse, SubmitSupportResultRequest>({
    mutationFn: (body, idempotencyKey) =>
      api.post<SubmitSupportResultResponse>(`/support-works/${enc(workId)}/submissions`, body, { idempotencyKey }),
    onSuccess: refresh,
  })
}

/** S10 (Helper): `Báo không thể hoàn thành` → `NEEDS_DISCUSSION`; không tự chuyển cho người khác. */
export function useReportUnable(workId: string, groupId: string) {
  const refresh = useRefreshSupportData(groupId)
  return useIdempotentMutation<SupportWork, ReportUnableRequest>({
    mutationFn: (body, idempotencyKey) => api.post<SupportWork>(`/support-works/${enc(workId)}/unable`, body, { idempotencyKey }),
    onSuccess: refresh,
  })
}

/** S10/S11 (TL): thống nhất sau `NEEDS_DISCUSSION` — Idempotency-Key BẮT BUỘC. */
export function useResolveDiscussion(workId: string, groupId: string) {
  const refresh = useRefreshSupportData(groupId)
  return useIdempotentMutation<SupportWork, ResolveDiscussionRequest>({
    mutationFn: (body, idempotencyKey) =>
      api.post<SupportWork>(`/support-works/${enc(workId)}/resolve-discussion`, body, { idempotencyKey }),
    onSuccess: refresh,
  })
}
