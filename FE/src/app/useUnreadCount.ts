import { useQuery } from '@tanstack/react-query'
import { useAuth } from './auth/authContext'
import { fetchUnreadCount, queryKeys } from './shellApi'

/** Số thông báo chưa đọc cho badge chuông (FE poll mỗi 60 giây — MVP in-app, spec S13). */
export function useUnreadCount(): number {
  const { status } = useAuth()
  const query = useQuery({
    queryKey: queryKeys.unread,
    queryFn: ({ signal }) => fetchUnreadCount(signal),
    enabled: status === 'authenticated',
    refetchInterval: 60_000,
    staleTime: 15_000,
  })
  return query.data?.total ?? 0
}
