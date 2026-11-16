/**
 * Đăng nhập Google bằng Google Identity Services (GIS): lấy `idToken` rồi gửi `POST /auth/google`.
 * Cần `VITE_GOOGLE_CLIENT_ID` (hợp đồng không trả client id) và server bật OAuth (`GET /auth/config` → `googleEnabled`).
 */
interface GoogleCredentialResponse {
  credential?: string
}
interface GooglePromptNotification {
  isNotDisplayed: () => boolean
  isSkippedMoment: () => boolean
}
interface GoogleIdApi {
  initialize: (config: { client_id: string; callback: (response: GoogleCredentialResponse) => void; cancel_on_tap_outside?: boolean }) => void
  prompt: (listener?: (notification: GooglePromptNotification) => void) => void
}
declare global {
  interface Window {
    google?: { accounts: { id: GoogleIdApi } }
  }
}

export type GoogleSignInFailure = 'blocked' | 'failed'

export class GoogleSignInError extends Error {
  readonly reason: GoogleSignInFailure
  constructor(reason: GoogleSignInFailure) {
    super(reason)
    this.name = 'GoogleSignInError'
    this.reason = reason
  }
}

const SCRIPT_SRC = 'https://accounts.google.com/gsi/client'
let scriptPromise: Promise<void> | null = null

function loadScript(): Promise<void> {
  if (window.google?.accounts?.id) return Promise.resolve()
  scriptPromise ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = SCRIPT_SRC
    script.async = true
    script.onload = () => resolve()
    script.onerror = () => {
      scriptPromise = null
      reject(new GoogleSignInError('blocked'))
    }
    document.head.appendChild(script)
  })
  return scriptPromise
}

export const GOOGLE_CLIENT_ID: string | undefined = import.meta.env.VITE_GOOGLE_CLIENT_ID || undefined

/** Mở hộp thoại đăng nhập Google và trả về ID token. Bị chặn/đóng sớm → `GoogleSignInError('blocked')`. */
export async function requestGoogleIdToken(clientId: string): Promise<string> {
  await loadScript()
  const api = window.google?.accounts?.id
  if (!api) throw new GoogleSignInError('failed')
  return new Promise<string>((resolve, reject) => {
    api.initialize({
      client_id: clientId,
      cancel_on_tap_outside: false,
      callback: (response) => (response.credential ? resolve(response.credential) : reject(new GoogleSignInError('failed'))),
    })
    api.prompt((notification) => {
      if (notification.isNotDisplayed() || notification.isSkippedMoment()) reject(new GoogleSignInError('blocked'))
    })
  })
}
