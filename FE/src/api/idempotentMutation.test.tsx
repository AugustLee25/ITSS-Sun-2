import type { ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError, CLIENT_ERROR } from './errors'
import { useIdempotentMutation } from './idempotentMutation'

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>{children}</QueryClientProvider>
)

describe('useIdempotentMutation', () => {
  it('dùng lại khóa khi chưa biết kết quả (mất mạng); đổi nội dung, thành công hoặc bị từ chối 4xx thì khóa mới', async () => {
    const keys: string[] = []
    const outcomes: Array<'network' | 'ok' | 'rejected'> = ['network', 'ok', 'ok', 'rejected', 'ok']
    const { result } = renderHook(
      () =>
        useIdempotentMutation<string, { n: number }>({
          mutationFn: async (_vars, key) => {
            keys.push(key)
            const outcome = outcomes.shift()
            if (outcome === 'network') throw new ApiError({ status: 0, code: CLIENT_ERROR.network, message: 'x' })
            if (outcome === 'rejected') throw new ApiError({ status: 422, code: 'VALIDATION_FAILED', message: 'x' })
            return 'ok'
          },
        }),
      { wrapper },
    )
    const run = (n: number) => act(async () => void (await result.current.mutateAsync({ n }).catch(() => undefined)))

    await run(1) // mất mạng
    await run(1) // thử lại cùng nội dung → cùng khóa
    expect(keys[1]).toBe(keys[0])
    await run(1) // đã thành công trước đó → khóa mới
    expect(keys[2]).not.toBe(keys[1])
    await run(2) // bị từ chối
    await run(2) // 4xx đã xóa khóa → khóa mới
    expect(keys[4]).not.toBe(keys[3])
  })
})
