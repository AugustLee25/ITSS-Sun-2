import { ApiError, CLIENT_ERROR, type ApiErrorBody } from './errors'
import { wantsIdempotencyKey } from './idempotency'
import { emitUnauthorized, getToken } from './session'

export type QueryValue = string | number | boolean | null | undefined | Array<string | number | boolean>

export interface RequestOptions {
  /** Query string; giá trị null/undefined bị bỏ, mảng lặp lại khóa. */
  query?: Record<string, QueryValue>
  /** Thân JSON (hoặc FormData). */
  body?: unknown
  headers?: Record<string, string>
  /** Truyền `signal` của React Query để hủy khi component unmount. */
  signal?: AbortSignal
  /**
   * `Idempotency-Key`. Mặc định (bỏ trống): tự sinh khóa mới cho các endpoint mà hợp đồng yêu cầu/khuyến nghị.
   * Muốn THỬ LẠI cùng một thao tác: tạo khóa một lần bằng `newIdempotencyKey()` rồi truyền lại ở mọi lần gọi.
   * `true` = luôn sinh khóa; `false` = không gửi.
   */
  idempotencyKey?: string | boolean
  /** Không gắn Bearer (vd. đăng nhập/đăng ký). */
  anonymous?: boolean
  /** Không kích hoạt luồng "401 → về S00" (vd. sai mật khẩu ở S00). */
  skipUnauthorizedHandler?: boolean
  timeoutMs?: number
}

export interface ApiClientConfig {
  baseUrl?: string
  getToken?: () => string | null
  onUnauthorized?: () => void
  fetchImpl?: typeof fetch
  timeoutMs?: number
}

export const DEFAULT_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

function buildQuery(query?: Record<string, QueryValue>): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) value.forEach((v) => params.append(key, String(v)))
    else params.append(key, String(value))
  }
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

async function parseJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return undefined
  return JSON.parse(text) as unknown
}

function toApiError(status: number, payload: unknown, statusText: string): ApiError {
  const body = (payload && typeof payload === 'object' ? payload : {}) as Partial<ApiErrorBody>
  return new ApiError({
    status,
    code: typeof body.code === 'string' && body.code ? body.code : `HTTP_${status}`,
    message: typeof body.message === 'string' && body.message ? body.message : statusText,
    fieldErrors: Array.isArray(body.fieldErrors) ? body.fieldErrors : [],
    traceId: typeof body.traceId === 'string' ? body.traceId : undefined,
  })
}

export class ApiClient {
  private readonly config: ApiClientConfig

  constructor(config: ApiClientConfig = {}) {
    this.config = config
  }

  async request<T = void>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const { baseUrl = DEFAULT_BASE_URL, fetchImpl = fetch, timeoutMs = 30_000 } = this.config
    const url = `${baseUrl}${path.startsWith('/') ? path : `/${path}`}${buildQuery(options.query)}`

    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
    const token = options.anonymous ? null : (this.config.getToken ?? getToken)()
    if (token) headers.Authorization = `Bearer ${token}`
    const wantsKey = options.idempotencyKey ?? (method.toUpperCase() !== 'GET' && wantsIdempotencyKey(method, path))
    if (wantsKey) {
      headers['Idempotency-Key'] = typeof wantsKey === 'string' ? wantsKey : newIdempotencyKey()
    }

    let body: BodyInit | undefined
    if (options.body instanceof FormData) {
      body = options.body
    } else if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
      body = JSON.stringify(options.body)
    }

    const timeout = AbortSignal.timeout(options.timeoutMs ?? timeoutMs)
    const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout

    let response: Response
    try {
      response = await fetchImpl(url, { method, headers, body, signal })
    } catch (error) {
      if (options.signal?.aborted) throw error // bị hủy chủ động: để React Query xử lý
      if (timeout.aborted) {
        throw new ApiError({ status: 0, code: CLIENT_ERROR.timeout, message: 'Request timed out' })
      }
      throw new ApiError({ status: 0, code: CLIENT_ERROR.network, message: 'Network error' })
    }

    let payload: unknown
    try {
      payload = await parseJson(response)
    } catch {
      if (response.ok) {
        throw new ApiError({
          status: response.status,
          code: CLIENT_ERROR.invalidResponse,
          message: 'Invalid server response',
        })
      }
    }

    if (!response.ok) {
      if (response.status === 401 && !options.skipUnauthorizedHandler) {
        ;(this.config.onUnauthorized ?? emitUnauthorized)()
      }
      throw toApiError(response.status, payload, response.statusText)
    }
    return payload as T
  }

  get<T>(path: string, options?: Omit<RequestOptions, 'body'>) {
    return this.request<T>('GET', path, options)
  }
  post<T = void>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>) {
    return this.request<T>('POST', path, { ...options, body })
  }
  put<T = void>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>) {
    return this.request<T>('PUT', path, { ...options, body })
  }
  patch<T = void>(path: string, body?: unknown, options?: Omit<RequestOptions, 'body'>) {
    return this.request<T>('PATCH', path, { ...options, body })
  }
  delete<T = void>(path: string, options?: RequestOptions) {
    return this.request<T>('DELETE', path, options)
  }
}

/** Client dùng chung: `import { api } from '@/api'`; đường dẫn tương đối `/api/v1`. */
export const api = new ApiClient()
