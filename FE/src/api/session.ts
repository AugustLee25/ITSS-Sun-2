/** Lưu token phiên (localStorage) và phát sự kiện 401 để AuthProvider đưa người dùng về S00. */
const TOKEN_KEY = 'hanare.token'

let memoryToken: string | null = null
const unauthorizedListeners = new Set<() => void>()

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // trình duyệt chặn storage
  }
}

export function getToken(): string | null {
  return storage()?.getItem(TOKEN_KEY) ?? memoryToken
}

export function setToken(token: string): void {
  memoryToken = token
  storage()?.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  memoryToken = null
  storage()?.removeItem(TOKEN_KEY)
}

export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

/** Gọi khi server trả 401: xóa token rồi báo cho các listener. */
export function emitUnauthorized(): void {
  clearToken()
  unauthorizedListeners.forEach((listener) => listener())
}
