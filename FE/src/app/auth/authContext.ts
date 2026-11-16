import { createContext, useContext } from 'react'
import type { AuthResponse, UserProfile } from '@/api/types'

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous' | 'error'
/** Vì sao phiên kết thúc: người dùng bấm đăng xuất, hay token hết hạn (server trả 401). */
export type SessionEnd = 'signed-out' | 'expired' | null

export interface AuthContextValue {
  status: AuthStatus
  user: UserProfile | null
  error: unknown
  sessionEnd: SessionEnd
  /**
   * Gọi sau khi S00 đăng nhập/đăng ký thành công: `signIn(await api.post<AuthResponse>('/auth/login', body, { anonymous: true, skipUnauthorizedHandler: true }))`.
   * Lưu token và nạp sẵn người dùng (không cần gọi lại /auth/me).
   */
  signIn: (auth: Pick<AuthResponse, 'accessToken' | 'user'>) => void
  /** Xóa token, xóa cache, về trạng thái chưa đăng nhập. */
  signOut: () => void
  /** Thử tải lại người dùng khi `status === 'error'`. */
  reload: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth phải nằm trong <AuthProvider>')
  return value
}

/** Người dùng hiện tại; ném lỗi nếu chưa đăng nhập (chỉ dùng bên trong route đã qua RequireAuth). */
export function useCurrentUser(): UserProfile {
  const { user } = useAuth()
  if (!user) throw new Error('useCurrentUser chỉ dùng trong vùng đã đăng nhập')
  return user
}
