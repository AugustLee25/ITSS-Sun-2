import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type {
  AuthConfigResponse,
  AuthResponse,
  DemoAccountsResponse,
  GoogleLoginRequest,
  InvitePreview,
  LoginRequest,
  RegisterRequest,
} from './types'

/** S00 gọi khi chưa có phiên: không gắn Bearer, và 401 (sai mật khẩu) KHÔNG được đá về S00 lần nữa. */
const ANONYMOUS = { anonymous: true, skipUnauthorizedHandler: true } as const

export const authKeys = {
  config: ['auth', 'config'] as const,
  demoAccounts: ['demo', 'accounts'] as const,
  invite: (code: string, anonymous: boolean) => ['invites', code, anonymous ? 'anonymous' : 'member'] as const,
}

export const login = (body: LoginRequest) => api.post<AuthResponse>('/auth/login', body, ANONYMOUS)
export const register = (body: RegisterRequest) => api.post<AuthResponse>('/auth/register', body, ANONYMOUS)
export const loginWithGoogle = (body: GoogleLoginRequest) => api.post<AuthResponse>('/auth/google', body, ANONYMOUS)

export function useAuthConfig() {
  return useQuery({
    queryKey: authKeys.config,
    queryFn: ({ signal }) => api.get<AuthConfigResponse>('/auth/config', { signal, ...ANONYMOUS }),
    staleTime: 5 * 60_000,
    retry: false,
  })
}

/** Tài khoản demo (chỉ có khi server chạy profile demo). Lỗi/404 được bỏ qua: đây chỉ là tiện ích. */
export function useDemoAccounts(enabled: boolean) {
  return useQuery({
    queryKey: authKeys.demoAccounts,
    queryFn: ({ signal }) => api.get<DemoAccountsResponse>('/demo/accounts', { signal, ...ANONYMOUS }),
    enabled,
    staleTime: 5 * 60_000,
    retry: false,
  })
}

/**
 * Xem trước lời mời (`GET /invites/{code}`). `anonymous = true` ở S00 (chưa đăng nhập);
 * đã đăng nhập (S02) thì gửi kèm token để server trả `alreadyMember`/`groupId`.
 */
export function useInvitePreview(code: string | null | undefined, options: { anonymous?: boolean } = {}) {
  const anonymous = options.anonymous ?? false
  return useQuery({
    queryKey: authKeys.invite(code ?? '', anonymous),
    queryFn: ({ signal }) =>
      api.get<InvitePreview>(`/invites/${encodeURIComponent(code ?? '')}`, {
        signal,
        ...(anonymous ? ANONYMOUS : { skipUnauthorizedHandler: true }),
      }),
    enabled: !!code,
    retry: false,
    staleTime: 60_000,
  })
}
