import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import type { ListNotificationsQuery, MarkAllReadRequest, MarkAllReadResponse, NotificationItem, OpenNotificationResponse, Page } from './types'

const enc = encodeURIComponent

/** Tiền tố chung: làm mới cả danh sách lẫn số chưa đọc ở chuông header (`['notifications','unread-count']`). */
const ALL = ['notifications'] as const

/** S13: danh sách (chưa đọc trước, mới nhất trước). FE poll/refetch (MVP in-app). */
export function useNotifications(query: ListNotificationsQuery) {
  return useQuery({
    queryKey: [...ALL, 'list', query] as const,
    queryFn: ({ signal }) => api.get<Page<NotificationItem>>('/notifications', { signal, query: { ...query } }),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  })
}

function useRefreshing<TData, TVars>(mutationFn: (vars: TVars) => Promise<TData>) {
  const queryClient = useQueryClient()
  return useMutation({ mutationFn, onSuccess: () => queryClient.invalidateQueries({ queryKey: ALL }) })
}

/** Click thông báo: đánh dấu đã đọc VÀ kiểm tra lại quyền/tồn tại của đích. */
export const useOpenNotification = () => useRefreshing((id: string) => api.post<OpenNotificationResponse>(`/notifications/${enc(id)}/open`))

/** Chỉ đổi trạng thái đọc, không đồng nghĩa việc đã xử lý. */
export const useMarkRead = () => useRefreshing((id: string) => api.patch<NotificationItem>(`/notifications/${enc(id)}/read`))

export const useMarkAllRead = () => useRefreshing((body: MarkAllReadRequest) => api.post<MarkAllReadResponse>('/notifications/read-all', body))
