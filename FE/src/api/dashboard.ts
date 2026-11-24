import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type { DashboardResponse, MemberProgressDetail, MemberTaskBoard } from './types'

const enc = encodeURIComponent

/** Tiền tố khóa để làm mới mọi bảng tổng quan của một nhóm (sau khi ghi task/kế hoạch/báo cáo...). */
export const dashboardKeyPrefix = (groupId: string) => ['dashboard', groupId] as const

export const dashboardKeys = {
  overview: (groupId: string, cycleId?: string | null) => ['dashboard', groupId, 'overview', cycleId ?? 'current'] as const,
  member: (groupId: string, userId: string, cycleId?: string | null) =>
    ['dashboard', groupId, 'member', userId, cycleId ?? 'current'] as const,
  memberTasks: (groupId: string, userId: string) => ['dashboard', groupId, 'member-tasks', userId] as const,
}

/**
 * Solution 2 · L1 (chỉ TL): TOÀN BỘ task của một Member — quá hạn/cần hỗ trợ lên đầu, DONE cuối — kèm khả năng
 * `Đưa lên Workspace`. Server phân nhóm và sắp xếp; FE chỉ hiển thị.
 */
export function useMemberTaskBoard(groupId: string, userId: string | null, enabled = true) {
  return useQuery({
    queryKey: dashboardKeys.memberTasks(groupId, userId ?? ''),
    queryFn: ({ signal }) => api.get<MemberTaskBoard>(`/groups/${enc(groupId)}/dashboard/members/${enc(userId ?? '')}/tasks`, { signal }),
    enabled: !!groupId && !!userId && enabled,
  })
}

/** S03: một request đủ dựng cả trang theo thứ tự ưu tiên của spec. `cycleId` bỏ trống = kỳ hiện tại. */
export function useDashboard(groupId: string, cycleId?: string | null) {
  return useQuery({
    queryKey: dashboardKeys.overview(groupId, cycleId),
    queryFn: ({ signal }) =>
      api.get<DashboardResponse>(`/groups/${enc(groupId)}/dashboard`, { signal, query: { cycleId: cycleId ?? undefined } }),
    enabled: !!groupId,
    staleTime: 15_000,
  })
}

/** S03: click khung tên → danh sách task của một thành viên trong kỳ (deadline, trạng thái nộp, kết quả duyệt). */
export function useMemberProgress(groupId: string, userId: string | null, cycleId?: string | null) {
  return useQuery({
    queryKey: dashboardKeys.member(groupId, userId ?? '', cycleId),
    queryFn: ({ signal }) =>
      api.get<MemberProgressDetail>(`/groups/${enc(groupId)}/dashboard/members/${enc(userId ?? '')}`, {
        signal,
        query: { cycleId: cycleId ?? undefined },
      }),
    enabled: !!groupId && !!userId,
  })
}
