import type { FieldValues, UseFormSetError } from 'react-hook-form'
import type { TFunction } from '@/i18n'
import type { ApiError as ApiErrorBody, ErrorCode, FieldError } from './types'

export type { ApiErrorBody, FieldError }

/** Mã lỗi do FE tự tạo khi không có phản hồi hợp lệ từ server. */
export const CLIENT_ERROR = {
  network: 'NETWORK_ERROR',
  timeout: 'TIMEOUT',
  invalidResponse: 'INVALID_RESPONSE',
} as const

export class ApiError extends Error {
  readonly status: number
  /** Mã nghiệp vụ của BE (`ErrorCode`) hoặc mã FE tự tạo (`CLIENT_ERROR`) / `HTTP_<status>`. */
  readonly code: ErrorCode | string
  readonly fieldErrors: FieldError[]
  readonly traceId?: string

  constructor(init: { status: number; code: ErrorCode | string; message: string; fieldErrors?: FieldError[]; traceId?: string }) {
    super(init.message)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    this.traceId = init.traceId
  }

  /** Không nhận được phản hồi (mất mạng / quá thời gian). */
  get isClientSide(): boolean {
    return this.status === 0
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

export function hasStatus(error: unknown, ...statuses: number[]): boolean {
  return isApiError(error) && statuses.includes(error.status)
}

/** 4xx không đáng thử lại tự động (dùng cho `retry` của React Query). */
export function isClientRequestError(error: unknown): boolean {
  return isApiError(error) && error.status >= 400 && error.status < 500
}

/** Thông điệp thân thiện cho người dùng: ưu tiên `message` do BE trả (đã là tiếng Việt), rồi tới mặc định theo mã. */
export function getErrorMessage(error: unknown, t: TFunction): string {
  if (!isApiError(error)) return t('errors.unknown')
  if (error.code === CLIENT_ERROR.network) return t('errors.network')
  if (error.code === CLIENT_ERROR.timeout) return t('errors.timeout')
  if (error.code === 'HELP_ALREADY_MATCHED') return t('copy.alreadyHelped')
  if (error.message && error.status >= 400 && error.status < 500) return error.message
  switch (error.status) {
    case 400:
      return t('errors.status400')
    case 401:
      return t('errors.status401')
    case 403:
      return t('errors.status403')
    case 404:
      return t('errors.status404')
    case 409:
      return t('errors.status409')
    case 422:
      return t('errors.status422')
    case 429:
      return t('errors.status429')
    default:
      return t('errors.status500')
  }
}

/** Gắn `fieldErrors` của BE vào react-hook-form: `applyFieldErrors(err, form.setError)`. */
export function applyFieldErrors<T extends FieldValues>(error: unknown, setError: UseFormSetError<T>): boolean {
  if (!isApiError(error) || error.fieldErrors.length === 0) return false
  const set = setError as unknown as (name: string, error: { type: string; message: string }) => void
  for (const { field, message } of error.fieldErrors) set(field, { type: 'server', message })
  return true
}
