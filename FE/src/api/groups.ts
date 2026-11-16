import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchMyGroups, queryKeys } from '@/app/shellApi'
import { api } from './client'
import { useIdempotentMutation } from './idempotentMutation'
import type { AcceptInviteResponse, CreateGroupRequest, CreateGroupResponse, Invite, ItemsResponse, Member } from './types'

const enc = encodeURIComponent

export const groupKeys = {
  members: (groupId: string) => ['groups', 'members', groupId] as const,
  invite: (groupId: string) => ['groups', 'invite', groupId] as const,
}

/** S01: nhóm của tôi (cùng khóa cache với GroupSwitcher ở khung ứng dụng). */
export function useMyGroups() {
  return useQuery({ queryKey: queryKeys.myGroups, queryFn: ({ signal }) => fetchMyGroups(signal) })
}

/** S02 (tạo): `POST /groups` — khuyến nghị Idempotency-Key, dùng lại khi thử lại cùng nội dung. */
export function useCreateGroup() {
  const queryClient = useQueryClient()
  return useIdempotentMutation<CreateGroupResponse, CreateGroupRequest>({
    mutationFn: (body, idempotencyKey) => api.post<CreateGroupResponse>('/groups', body, { idempotencyKey }),
    onSuccess: (data) => {
      queryClient.setQueryData(queryKeys.group(data.group.id), data.group)
      void queryClient.invalidateQueries({ queryKey: queryKeys.myGroups })
    },
  })
}

/** S02 (tham gia): `POST /invites/{code}/accept`. */
export function useAcceptInvite() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.post<AcceptInviteResponse>(`/invites/${enc(code)}/accept`),
    onSuccess: ({ group }) => {
      queryClient.setQueryData(queryKeys.group(group.id), group)
      void queryClient.invalidateQueries({ queryKey: queryKeys.myGroups })
    },
  })
}

export function useMembers(groupId: string) {
  return useQuery({
    queryKey: groupKeys.members(groupId),
    queryFn: async ({ signal }) => (await api.get<ItemsResponse<Member>>(`/groups/${enc(groupId)}/members`, { signal })).items,
    enabled: !!groupId,
    staleTime: 60_000,
  })
}

/** Mã mời hiện tại của nhóm (chỉ TL). 404 `INVITE_NOT_FOUND` = đã thu hồi, chưa tạo lại. */
export function useGroupInvite(groupId: string, enabled = true) {
  return useQuery({
    queryKey: groupKeys.invite(groupId),
    queryFn: ({ signal }) => api.get<Invite>(`/groups/${enc(groupId)}/invite`, { signal }),
    enabled: enabled && !!groupId,
    retry: false,
  })
}

export function useCreateInvite(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<Invite>(`/groups/${enc(groupId)}/invite`),
    onSuccess: (invite) => queryClient.setQueryData(groupKeys.invite(groupId), invite),
  })
}

export function useRevokeInvite(groupId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.delete(`/groups/${enc(groupId)}/invite`),
    onSuccess: () => queryClient.removeQueries({ queryKey: groupKeys.invite(groupId) }),
  })
}
