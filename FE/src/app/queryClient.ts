import { QueryClient } from '@tanstack/react-query'
import { isClientRequestError } from '@/api/errors'

/**
 * Cấu hình TanStack Query dùng chung.
 * - Không tự thử lại lỗi 4xx (401/403/404/409/422 là kết quả nghiệp vụ, không phải lỗi tạm thời).
 * - Lỗi mạng / 5xx: thử lại tối đa 2 lần.
 * - Mutation không tự thử lại (tránh gửi trùng; dùng Idempotency-Key nếu cần thử lại có chủ đích).
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: true,
        retry: (failureCount, error) => !isClientRequestError(error) && failureCount < 2,
      },
      mutations: { retry: false },
    },
  })
}

export const queryClient = createQueryClient()
