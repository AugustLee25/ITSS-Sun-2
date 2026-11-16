import { api } from '@/api/client'
import type { Group, GroupCard, ItemsResponse, UnreadCountResponse, UserProfile } from '@/api/types'

/**
 * Các truy vấn mà KHUNG ỨNG DỤNG cần (AuthProvider, GroupLayout). Đường dẫn theo `ApiEndpoints` trong `src/api/types.ts`.
 * `queryKeys` là nơi duy nhất định nghĩa khóa cache dùng chung — dùng để invalidate từ các màn hình khác
 * (vd. sau khi đọc thông báo: `queryClient.invalidateQueries({ queryKey: queryKeys.unread })`).
 */
export const queryKeys = {
  me: ['auth', 'me'] as const,
  myGroups: ['groups', 'list'] as const,
  group: (groupId: string) => ['groups', 'detail', groupId] as const,
  unread: ['notifications', 'unread-count'] as const,
}

export const fetchCurrentUser = (signal?: AbortSignal) => api.get<UserProfile>('/auth/me', { signal })

export const fetchMyGroups = async (signal?: AbortSignal): Promise<GroupCard[]> =>
  (await api.get<ItemsResponse<GroupCard>>('/groups', { signal })).items

export const fetchGroup = (groupId: string, signal?: AbortSignal) =>
  api.get<Group>(`/groups/${encodeURIComponent(groupId)}`, { signal })

export const fetchUnreadCount = (signal?: AbortSignal) => api.get<UnreadCountResponse>('/notifications/unread-count', { signal })
