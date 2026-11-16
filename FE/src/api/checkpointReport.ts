import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { useIdempotentMutation } from './idempotentMutation'
import { invalidateTaskData } from './tasks'
import type { MyReportResponse, SaveReportRequest, SubmitReportRequest } from './types'

const enc = encodeURIComponent
const reportPath = (groupId: string, cycleId: string) => `/groups/${enc(groupId)}/checkpoint-cycles/${enc(cycleId)}/my-report`
export const reportKey = (groupId: string, cycleId: string) => ['checkpoint-report', groupId, cycleId] as const

/** S06: báo cáo checkpoint của tôi (server điền sẵn minh chứng, khung mở/chốt, `canEdit`). */
export function useMyReport(groupId: string, cycleId: string | undefined) {
  return useQuery({
    queryKey: reportKey(groupId, cycleId ?? ''),
    queryFn: ({ signal }) => api.get<MyReportResponse>(reportPath(groupId, cycleId ?? ''), { signal }),
    enabled: !!groupId && !!cycleId,
    staleTime: 0,
    retry: false,
  })
}

/** S06 `Lưu nháp`. */
export function useSaveReport(groupId: string, cycleId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: SaveReportRequest) => api.put<MyReportResponse>(reportPath(groupId, cycleId), body),
    onSuccess: (data) => queryClient.setQueryData(reportKey(groupId, cycleId), data),
  })
}

/** S06 `Gửi báo cáo tuần` — Idempotency-Key BẮT BUỘC. */
export function useSubmitReport(groupId: string, cycleId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<MyReportResponse, SubmitReportRequest>({
    mutationFn: (body, idempotencyKey) => api.post<MyReportResponse>(`${reportPath(groupId, cycleId)}/submit`, body, { idempotencyKey }),
    onSuccess: (data) => {
      queryClient.setQueryData(reportKey(groupId, cycleId), data)
      invalidateTaskData(queryClient, groupId)
    },
  })
}
