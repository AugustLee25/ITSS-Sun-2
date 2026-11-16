import { createContext, useContext } from 'react'

export type ToastTone = 'success' | 'error' | 'info' | 'warning'

export interface ToastOptions {
  tone?: ToastTone
  title?: string
  message: string
  /** ms; 0 = không tự ẩn. Mặc định 5000 (lỗi: 8000). */
  duration?: number
}

export interface ToastApi {
  show: (options: ToastOptions) => string
  success: (message: string, title?: string) => string
  error: (message: string, title?: string) => string
  info: (message: string, title?: string) => string
  warning: (message: string, title?: string) => string
  dismiss: (id: string) => void
}

const noop = () => ''

export const ToastContext = createContext<ToastApi>({
  show: noop,
  success: noop,
  error: noop,
  info: noop,
  warning: noop,
  dismiss: () => undefined,
})

/** `const toast = useToast(); toast.success('Đã lưu')` — cần `ToastProvider` ở gốc ứng dụng. */
export function useToast(): ToastApi {
  return useContext(ToastContext)
}
