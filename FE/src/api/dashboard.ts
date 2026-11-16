import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type { DashboardResponse, MemberProgressDetail } from './types'

const enc = encodeURIComponent

/** Tiền tố khóa để làm mới mọi bảng tổng quan của một nhóm (sau khi ghi task/kế hoạch/báo cáo...). */
export const dashboardKeyPrefix = (groupId: string) => ['dashboard', groupId] as const

export const dashboardKeys = {
  overview: (groupId: string, cycleId?: string | null) => ['dashboard', groupId, 'overview', cycleId ?? 'current'] as const,
  member: (groupId: string, userId: string, cycleId?: string | null) =>
    ['dashboard', groupId, 'member', userId, cycleId ?? 'current'] as const,
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
