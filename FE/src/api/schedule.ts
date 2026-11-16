import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { useIdempotentMutation } from './idempotentMutation'
import type {
  CheckpointCycle,
  CheckpointSchedule,
  ItemsResponse,
  ListCyclesQuery,
  Page,
  SaveScheduleRequest,
  SaveScheduleResponse,
  SchedulePreviewRequest,
  SchedulePreviewResponse,
} from './types'

const enc = encodeURIComponent

export const scheduleKeys = {
  schedules: (groupId: string) => ['schedule', 'list', groupId] as const,
  cycles: (groupId: string, query?: ListCyclesQuery) => ['schedule', 'cycles', groupId, query ?? {}] as const,
  cyclesAll: (groupId: string) => ['schedule', 'cycles', groupId] as const,
}

/**
 * Xem trước lịch checkpoint (`POST /checkpoint-schedules/preview`, không ghi DB). Giờ UTC do SERVER sinh, FE chỉ hiển thị.
 * Truyền `null` khi dữ liệu nhập chưa đủ → không gọi. Giữ kết quả cũ trong lúc tải kết quả mới (tránh nhấp nháy).
 */
export function useSchedulePreview(request: SchedulePreviewRequest | null) {
  return useQuery({
    queryKey: ['schedule', 'preview', request] as const,
    queryFn: ({ signal }) => api.post<SchedulePreviewResponse>('/checkpoint-schedules/preview', request, { signal }),
    enabled: request !== null,
    placeholderData: keepPreviousData,
    retry: false,
    staleTime: 60_000,
  })
}

export function useSchedules(groupId: string, enabled = true) {
  return useQuery({
    queryKey: scheduleKeys.schedules(groupId),
    queryFn: async ({ signal }) =>
      (await api.get<ItemsResponse<CheckpointSchedule>>(`/groups/${enc(groupId)}/checkpoint-schedules`, { signal })).items,
    enabled: enabled && !!groupId,
  })
}

/** TL lưu lịch mới thay mặt nhóm; chỉ áp dụng cho kỳ chưa mở (server quyết định). */
export function useSaveSchedule(groupId: string) {
  const queryClient = useQueryClient()
  return useIdempotentMutation<SaveScheduleResponse, SaveScheduleRequest>({
    mutationFn: (body, idempotencyKey) =>
      api.post<SaveScheduleResponse>(`/groups/${enc(groupId)}/checkpoint-schedules`, body, { idempotencyKey }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: scheduleKeys.schedules(groupId) })
      void queryClient.invalidateQueries({ queryKey: scheduleKeys.cyclesAll(groupId) })
      void queryClient.invalidateQueries({ queryKey: ['dashboard', groupId] })
    },
  })
}

/** Danh sách kỳ ("tuần" của S04). */
export function useCycles(groupId: string, query: ListCyclesQuery = { sort: 'desc', size: 30 }) {
  return useQuery({
    queryKey: scheduleKeys.cycles(groupId, query),
    queryFn: ({ signal }) => api.get<Page<CheckpointCycle>>(`/groups/${enc(groupId)}/checkpoint-cycles`, { signal, query: { ...query } }),
    enabled: !!groupId,
    staleTime: 60_000,
  })
}
