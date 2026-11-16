import { useRef } from 'react'
import { useMutation, type UseMutationOptions } from '@tanstack/react-query'
import { newIdempotencyKey } from './client'
import { isApiError } from './errors'

type Options<TData, TVariables> = Omit<UseMutationOptions<TData, Error, TVariables>, 'mutationFn'> & {
  /** Nhận thêm `Idempotency-Key` để truyền vào `api.post(..., { idempotencyKey })`. */
  mutationFn: (variables: TVariables, idempotencyKey: string) => Promise<TData>
}

/**
 * `useMutation` cho thao tác ghi nhạy cảm (spec 7.3: "người dùng submit nhiều lần").
 * - Cùng nội dung gửi lại sau khi KHÔNG biết kết quả (mất mạng, quá thời gian, 5xx) → dùng lại cùng `Idempotency-Key`,
 *   server trả đúng kết quả cũ thay vì thực hiện lần hai.
 * - Nội dung đổi, đã thành công, hoặc server từ chối rõ ràng (4xx) → lần sau dùng khóa mới.
 */
export function useIdempotentMutation<TData, TVariables = void>(options: Options<TData, TVariables>) {
  const last = useRef<{ fingerprint: string; key: string } | null>(null)
  const { mutationFn, ...rest } = options
  return useMutation<TData, Error, TVariables>({
    ...rest,
    mutationFn: async (variables) => {
      const fingerprint = JSON.stringify(variables) ?? ''
      if (last.current?.fingerprint !== fingerprint) last.current = { fingerprint, key: newIdempotencyKey() }
      const key = last.current.key
      try {
        const data = await mutationFn(variables, key)
        last.current = null
        return data
      } catch (error) {
        const outcomeUnknown = isApiError(error) && (error.status === 0 || error.status >= 500)
        if (!outcomeUnknown) last.current = null
        throw error
      }
    },
  })
}
