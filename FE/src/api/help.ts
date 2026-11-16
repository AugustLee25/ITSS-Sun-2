import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api } from './client'
import { dashboardKeyPrefix } from './dashboard'
import { useIdempotentMutation } from './idempotentMutation'
import type {
  AcceptOfferRequest,
  AcceptOfferResponse,
  CancelHelpRequestRequest,
  CreateHelpRequestRequest,
  CreateOfferRequest,
  HelpRequestCard,
  HelpRequestDetail,
  ListHelpRequestsQuery,
  Page,
  ReconfirmOfferRequest,
  SupportOffer,
} from './types'

const enc = encodeURIComponent

export const helpKeys = {
  list: (groupId: string, query: ListHelpRequestsQuery) => ['help', 'list', groupId, query] as const,
  detail: (id: string) => ['help', 'detail', id] as const,
}

/** Sau mọi thao tác ghi: làm mới yêu cầu hỗ trợ, task (đổi người thực hiện), bảng tổng quan, điểm, thông báo. */
function invalidateHelp(queryClient: QueryClient, groupId: string) {
  for (const key of [['help'], ['tasks'], ['points'], ['support'], ['notifications'], dashboardKeyPrefix(groupId)]) {
    void queryClient.invalidateQueries({ queryKey: key })
  }
}

/** S08: 4 tab (`OPEN` | `MY_OFFERS` | `MY_WORK` | `COMPLETED`), lọc theo độ khó/deadline/điểm/công sức. */
export function useHelpRequests(groupId: string, query: ListHelpRequestsQuery) {
  return useQuery({
    queryKey: helpKeys.list(groupId, query),
    queryFn: ({ signal }) => api.get<Page<HelpRequestCard>>(`/groups/${enc(groupId)}/help-requests`, { signal, query: { ...query } }),
    enabled: !!groupId,
    staleTime: 15_000,
  })
}

/** S09. */
export function useHelpRequest(id: string | undefined) {
  return useQuery({
    queryKey: helpKeys.detail(id ?? ''),
    queryFn: ({ signal }) => api.get<HelpRequestDetail>(`/help-requests/${enc(id ?? '')}`, { signal }),
    enabled: !!id,
    retry: false,
  })
}

/** S08 (TL): đăng yêu cầu cho toàn bộ task — Idempotency-Key BẮT BUỘC; điểm P_t do server điền. */
export function useCreateHelpRequest(groupId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<HelpRequestDetail, CreateHelpRequestRequest>({
    mutationFn: (body, idempotencyKey) => api.post<HelpRequestDetail>(`/groups/${enc(groupId)}/help-requests`, body, { idempotencyKey }),
    onSuccess: () => invalidateHelp(queryClient, groupId),
  })
}

/** S09 (TL): đóng yêu cầu khi còn `OPEN` (confirm dialog ở UI). */
export function useCancelHelpRequest(groupId: string, id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CancelHelpRequestRequest) => api.post<HelpRequestDetail>(`/help-requests/${enc(id)}/cancel`, body),
    onSuccess: () => invalidateHelp(queryClient, groupId),
  })
}

/** S09 (thành viên): `Gửi đề nghị` — tự nguyện, không ghi điểm lúc đề nghị. */
export function useCreateOffer(groupId: string, id: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<SupportOffer, CreateOfferRequest>({
    mutationFn: (body, idempotencyKey) => api.post<SupportOffer>(`/help-requests/${enc(id)}/offers`, body, { idempotencyKey }),
    onSuccess: () => invalidateHelp(queryClient, groupId),
  })
}

/** S09: `Rút đề nghị` (không cần giải thích). */
export function useWithdrawOffer(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (offerId: string) => api.post<SupportOffer>(`/support-offers/${enc(offerId)}/withdraw`),
    onSuccess: () => invalidateHelp(queryClient, groupId),
  })
}

/** S09: xác nhận lại đề nghị sau khi phạm vi yêu cầu đổi. */
export function useReconfirmOffer(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ offerId, ...body }: ReconfirmOfferRequest & { offerId: string }) =>
      api.post<SupportOffer>(`/support-offers/${enc(offerId)}/reconfirm`, body),
    onSuccess: () => invalidateHelp(queryClient, groupId),
  })
}

/** S09 (TL): `Chấp thuận` một đề nghị — Idempotency-Key BẮT BUỘC; không ghi điểm lúc nhận. */
export function useAcceptOffer(groupId: string, id: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<AcceptOfferResponse, AcceptOfferRequest>({
    mutationFn: (body, idempotencyKey) => api.post<AcceptOfferResponse>(`/help-requests/${enc(id)}/accept-offer`, body, { idempotencyKey }),
    onSuccess: () => invalidateHelp(queryClient, groupId),
  })
}
