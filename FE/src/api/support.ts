import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api } from './client'
import { dashboardKeyPrefix } from './dashboard'
import { useIdempotentMutation } from './idempotentMutation'
import type {
  CreateRiskReportRequest,
  CreateSupportRequestRequest,
  ListRiskReportsQuery,
  Page,
  ResolveRiskRequest,
  RiskReport,
  SupportRequest,
  SupportRequestDecisionRequest,
} from './types'

const enc = encodeURIComponent

export const supportKeys = {
  detail: (id: string) => ['support', 'detail', id] as const,
  risks: (groupId: string, query: ListRiskReportsQuery) => ['support', 'risks', groupId, query] as const,
}

function invalidateSupport(queryClient: QueryClient, groupId: string) {
  void queryClient.invalidateQueries({ queryKey: ['support'] })
  void queryClient.invalidateQueries({ queryKey: ['notifications'] })
  void queryClient.invalidateQueries({ queryKey: dashboardKeyPrefix(groupId) })
}

/** S07: đề nghị hỗ trợ riêng — chỉ người gửi và TL đọc được (403 với người khác). */
export function useSupportRequest(id: string | undefined) {
  return useQuery({
    queryKey: supportKeys.detail(id ?? ''),
    queryFn: ({ signal }) => api.get<SupportRequest>(`/support-requests/${enc(id ?? '')}`, { signal }),
    enabled: !!id,
    retry: false,
  })
}

/** S07 (thành viên): `Gửi TL xem xét`. */
export function useCreateSupportRequest(groupId: string, taskId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<SupportRequest, CreateSupportRequestRequest>({
    mutationFn: (body, idempotencyKey) => api.post<SupportRequest>(`/tasks/${enc(taskId)}/support-requests`, body, { idempotencyKey }),
    onSuccess: () => invalidateSupport(queryClient, groupId),
  })
}

/** S07 (TL): `Đã xử lý, không cần đăng hỗ trợ` / `Cần trao đổi thêm`. (Đăng công khai đi qua S08.) */
export function useDecideSupportRequest(groupId: string, id: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<SupportRequest, SupportRequestDecisionRequest>({
    mutationFn: (body, idempotencyKey) => api.post<SupportRequest>(`/support-requests/${enc(id)}/decision`, body, { idempotencyKey }),
    onSuccess: () => invalidateSupport(queryClient, groupId),
  })
}

/** Risk chung của nhóm (chỉ `publicSummary`, không lộ nội dung riêng). */
export function useRiskReports(groupId: string, query: ListRiskReportsQuery, enabled = true) {
  return useQuery({
    queryKey: supportKeys.risks(groupId, query),
    queryFn: ({ signal }) => api.get<Page<RiskReport>>(`/groups/${enc(groupId)}/risk-reports`, { signal, query: { ...query } }),
    enabled: enabled && !!groupId,
  })
}

export function useCreateRiskReport(groupId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<RiskReport, CreateRiskReportRequest>({
    mutationFn: (body, idempotencyKey) => api.post<RiskReport>(`/groups/${enc(groupId)}/risk-reports`, body, { idempotencyKey }),
    onSuccess: () => invalidateSupport(queryClient, groupId),
  })
}

export function useResolveRisk(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: ResolveRiskRequest & { id: string }) => api.post<RiskReport>(`/risk-reports/${enc(id)}/resolve`, body),
    onSuccess: () => invalidateSupport(queryClient, groupId),
  })
}
