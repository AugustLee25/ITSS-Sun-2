import { createContext, useContext } from 'react'
import { GroupRole, type Group } from '@/api/types'

export interface GroupContextValue {
  groupId: string
  group: Group
  /** Vai trò của người dùng hiện tại trong nhóm này (do server trả). */
  role: GroupRole
  isLeader: boolean
  /** IANA time zone của nhóm — mọi thời gian hiển thị theo múi giờ này. */
  timeZone: string
}

export const GroupContext = createContext<GroupContextValue | null>(null)

/** Nhóm đang xem (chỉ dùng trong route `/groups/:groupId/...`, sau khi GroupLayout đã xác nhận quyền). */
export function useGroup(): GroupContextValue {
  const value = useContext(GroupContext)
  if (!value) throw new Error('useGroup chỉ dùng bên trong GroupLayout')
  return value
}

/** Như `useGroup` nhưng trả null ngoài nhóm (dùng ở component dùng chung). */
export function useOptionalGroup(): GroupContextValue | null {
  return useContext(GroupContext)
}

export const isLeaderRole = (role: GroupRole) => role === GroupRole.LEAD
