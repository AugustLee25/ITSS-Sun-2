import { render } from '@testing-library/react'
import { RouterProvider } from 'react-router/dom'
import { vi } from 'vitest'
import { clearToken, setToken } from '@/api/session'
import { GroupRole, type UserRef } from '@/api/types'
import { AppProviders } from '@/app/providers'
import { createQueryClient } from '@/app/queryClient'
import { createTestRouter } from '@/app/router'
import { GROUP_ID, MINH, groupCard, groupFor, profileOf } from './fixtures'

/** Phản hồi có mã HTTP tùy ý: `reply({ code: 'X', message: '...', fieldErrors: [], traceId: 't' }, 422)`. */
export class Reply {
  readonly body: unknown
  readonly status: number
  constructor(body: unknown, status = 200) {
    this.body = body
    this.status = status
  }
}
export const reply = (body: unknown, status = 200) => new Reply(body, status)
export const apiError = (status: number, code: string, message: string, fieldErrors: Array<{ field: string; message: string }> = []) =>
  new Reply({ code, message, fieldErrors, traceId: 'trace-1' }, status)

export interface Call {
  method: string
  path: string
  search: URLSearchParams
  headers: Headers
  body: unknown
}
export type Handler = ((call: Call) => unknown) | object
export type ApiRoute = [method: string, pattern: string | RegExp, handler: Handler]

function matches(pattern: string | RegExp, path: string): boolean {
  return typeof pattern === 'string' ? pattern === path : pattern.test(path)
}

/** Giả lập BE bằng `fetch` giả: tuyến khai báo trước thắng; không khớp → 404 `NOT_FOUND`. Ghi lại mọi lời gọi. */
export function mockApi(routes: ApiRoute[]) {
  const calls: Call[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = (init?.method ?? 'GET').toUpperCase()
    const rawBody = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined
    const call: Call = { method, path, search: url.searchParams, headers: new Headers(init?.headers), body: rawBody }
    calls.push(call)
    const route = routes.find(([m, p]) => m === method && matches(p, path))
    if (!route) return new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'Không có tuyến giả lập', fieldErrors: [], traceId: 't' }), { status: 404 })
    const handler = route[2]
    const result = typeof handler === 'function' ? (handler as (c: Call) => unknown)(call) : handler
    const out = result instanceof Reply ? result : new Reply(result)
    return new Response(out.status === 204 ? null : JSON.stringify(out.body), { status: out.status, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  return {
    calls,
    fetchMock,
    /** Các lời gọi ghi (không phải GET) tới `path`. */
    writes: (method: string, path: string | RegExp) => calls.filter((c) => c.method === method && matches(path, c.path)),
  }
}

/** Tuyến khung ứng dụng luôn cần (người dùng, chuông, danh sách nhóm, nhóm hiện tại). */
export function shellRoutes(user: UserRef = MINH, role: GroupRole = GroupRole.LEAD): ApiRoute[] {
  return [
    ['GET', '/auth/me', profileOf(user)],
    ['GET', '/notifications/unread-count', { total: 0, actionRequired: 0, byGroup: [] }],
    ['GET', '/groups', { items: [groupCard({ myRole: role })] }],
    ['GET', `/groups/${GROUP_ID}`, groupFor(role)],
  ]
}

/** Dựng ứng dụng thật (router + providers) tại `path`, đã đăng nhập (trừ khi `signedIn: false`). */
export function renderApp(path: string, options: { signedIn?: boolean } = {}) {
  if (options.signedIn !== false) setToken('test-token')
  else clearToken()
  const router = createTestRouter([path])
  const utils = render(
    <AppProviders queryClient={createQueryClient()}>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { router, ...utils }
}
