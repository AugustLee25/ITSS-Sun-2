import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { clearToken, getToken, onUnauthorized, setToken } from '@/api/session'
import { fetchCurrentUser, queryKeys } from '../shellApi'
import { AuthContext, type AuthContextValue, type SessionEnd } from './authContext'

/**
 * Giữ token (localStorage) + tải người dùng hiện tại. Không lưu trạng thái nghiệp vụ ở trình duyệt.
 * Server trả 401 → ApiClient phát sự kiện → provider xóa phiên → RequireAuth chuyển về S00.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [token, setTokenState] = useState<string | null>(() => getToken())
  const [sessionEnd, setSessionEnd] = useState<SessionEnd>(null)

  const me = useQuery({
    queryKey: queryKeys.me,
    queryFn: ({ signal }) => fetchCurrentUser(signal),
    enabled: !!token,
    staleTime: Infinity,
    retry: false,
  })

  useEffect(
    () =>
      onUnauthorized(() => {
        setTokenState(null)
        setSessionEnd('expired')
        queryClient.clear()
      }),
    [queryClient],
  )

  const signIn = useCallback<AuthContextValue['signIn']>(
    ({ accessToken, user }) => {
      queryClient.clear()
      setToken(accessToken)
      queryClient.setQueryData(queryKeys.me, user)
      setTokenState(accessToken)
      setSessionEnd(null)
    },
    [queryClient],
  )

  const signOut = useCallback(() => {
    clearToken()
    setTokenState(null)
    setSessionEnd('signed-out')
    queryClient.clear()
  }, [queryClient])

  const reload = useCallback(() => {
    void me.refetch()
  }, [me])

  const value = useMemo<AuthContextValue>(() => {
    const status = !token ? 'anonymous' : me.data ? 'authenticated' : me.isError ? 'error' : 'loading'
    return {
      status,
      user: token ? (me.data ?? null) : null,
      error: me.error,
      sessionEnd,
      signIn,
      signOut,
      reload,
    }
  }, [token, me.isError, me.data, me.error, sessionEnd, signIn, signOut, reload])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
