import { paths } from './routes'

/** Điểm đến mặc định sau đăng nhập (S01). */
export const DEFAULT_AFTER_LOGIN = paths.groups()

/** Chỉ cho phép đường dẫn nội bộ (chống open-redirect). */
export function isSafeInternalPath(path: string | null | undefined): path is string {
  return !!path && path.startsWith('/') && !path.startsWith('//') && !path.startsWith('/\\')
}

/** Mã lời mời có trong URL (`/join/:code` hoặc `?invite=`), nếu có. */
function inviteFrom(location: { pathname: string; search?: string }): string | null {
  const fromQuery = new URLSearchParams(location.search ?? '').get('invite')
  if (fromQuery) return fromQuery
  const match = /^\/join\/([^/]+)\/?$/.exec(location.pathname)
  return match?.[1] ? decodeURIComponent(match[1]) : null
}

/** Tạo URL S00 giữ lại đích cũ (`next`) và mã lời mời (`invite`) — spec S00 "chuyển tiếp lời mời". */
export function buildLoginUrl(location: { pathname: string; search?: string; hash?: string }): string {
  const next = `${location.pathname}${location.search ?? ''}${location.hash ?? ''}`
  const params = new URLSearchParams()
  if (next !== '/' && next !== DEFAULT_AFTER_LOGIN) params.set('next', next)
  const invite = inviteFrom(location)
  if (invite) params.set('invite', invite)
  const qs = params.toString()
  return qs ? `${paths.login()}?${qs}` : paths.login()
}

/**
 * Đích sau đăng nhập, đọc từ query của S00: `?next=` (nếu an toàn) → `?invite=` (S02 nhận lời mời) → S01.
 */
export function resolvePostLoginPath(search: string | URLSearchParams): string {
  const params = typeof search === 'string' ? new URLSearchParams(search) : search
  const next = params.get('next')
  if (isSafeInternalPath(next)) return next
  const invite = params.get('invite')
  if (invite) return paths.groupJoin(invite)
  return DEFAULT_AFTER_LOGIN
}
