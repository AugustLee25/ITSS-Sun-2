import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { api } from './client'
import type {
  GroupPointsSummary,
  LedgerEntryDetail,
  MyPointsResponse,
  Page,
  PointHistoryGroup,
  PointHistoryItem,
  PointTaskRow,
  SimulatePointsRequest,
  SimulatePointsResponse,
  SimulationCandidatesResponse,
} from './types'

const enc = encodeURIComponent

/** Tiền tố `['points']` trùng với nơi khác gọi `invalidateQueries` sau khi duyệt/đổi người nhận điểm. */
export const pointKeys = {
  me: (groupId: string) => ['points', 'me', groupId] as const,
  tasks: (groupId: string, page: number) => ['points', 'tasks', groupId, page] as const,
  ledger: (groupId: string, group: string, page: number) => ['points', 'ledger', groupId, group, page] as const,
  entry: (groupId: string, entryId: string) => ['points', 'entry', groupId, entryId] as const,
  summary: (groupId: string) => ['points', 'summary', groupId] as const,
  candidates: (groupId: string) => ['points', 'candidates', groupId] as const,
}

export const POINTS_PAGE_SIZE = 10

/** S12 "Điểm của tôi": mọi số đều do server tính; lỗi mạng KHÔNG được thay bằng 0. */
export function useMyPoints(groupId: string) {
  return useQuery({
    queryKey: pointKeys.me(groupId),
    queryFn: ({ signal }) => api.get<MyPointsResponse>(`/groups/${enc(groupId)}/points/me`, { signal }),
    enabled: !!groupId,
  })
}

export function useMyPointTasks(groupId: string, page: number, enabled: boolean) {
  return useQuery({
    queryKey: pointKeys.tasks(groupId, page),
    queryFn: ({ signal }) =>
      api.get<Page<PointTaskRow>>(`/groups/${enc(groupId)}/points/me/tasks`, { signal, query: { page, size: POINTS_PAGE_SIZE } }),
    enabled: enabled && !!groupId,
    placeholderData: keepPreviousData,
  })
}

export function useMyLedger(groupId: string, group: PointHistoryGroup | 'ALL', page: number, enabled: boolean) {
  return useQuery({
    queryKey: pointKeys.ledger(groupId, group, page),
    queryFn: ({ signal }) =>
      api.get<Page<PointHistoryItem>>(`/groups/${enc(groupId)}/points/me/ledger`, {
        signal,
        query: { group: group === 'ALL' ? undefined : group, page, size: POINTS_PAGE_SIZE },
      }),
    enabled: enabled && !!groupId,
    placeholderData: keepPreviousData,
  })
}

export function useLedgerEntry(groupId: string, entryId: string | null) {
  return useQuery({
    queryKey: pointKeys.entry(groupId, entryId ?? ''),
    queryFn: ({ signal }) => api.get<LedgerEntryDetail>(`/groups/${enc(groupId)}/points/ledger/${enc(entryId ?? '')}`, { signal }),
    enabled: !!groupId && !!entryId,
  })
}

export function useGroupPointsSummary(groupId: string, enabled: boolean) {
  return useQuery({
    queryKey: pointKeys.summary(groupId),
    queryFn: ({ signal }) => api.get<GroupPointsSummary>(`/groups/${enc(groupId)}/points/summary`, { signal }),
    enabled: enabled && !!groupId,
  })
}

export function useSimulationCandidates(groupId: string, enabled: boolean) {
  return useQuery({
    queryKey: pointKeys.candidates(groupId),
    queryFn: ({ signal }) => api.get<SimulationCandidatesResponse>(`/groups/${enc(groupId)}/points/me/simulation-candidates`, { signal }),
    enabled: enabled && !!groupId,
  })
}

/** `POST …/points/me/simulate` chỉ đọc: không ghi DB/điểm, không cần Idempotency-Key. */
export function useSimulatePoints(groupId: string) {
  return useMutation({
    mutationFn: (body: SimulatePointsRequest) => api.post<SimulatePointsResponse>(`/groups/${enc(groupId)}/points/me/simulate`, body),
  })
}
