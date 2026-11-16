import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from './client'
import { ApiError, CLIENT_ERROR, applyFieldErrors } from './errors'
import { clearToken, getToken, onUnauthorized, setToken } from './session'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' }, ...init })
}

function makeClient(fetchImpl: typeof fetch, extra: ConstructorParameters<typeof ApiClient>[0] = {}) {
  return new ApiClient({ baseUrl: '/api/v1', fetchImpl, ...extra })
}

afterEach(() => {
  clearToken()
  vi.restoreAllMocks()
})

describe('ApiClient', () => {
  it('gắn Bearer token, ghép query và parse JSON', async () => {
    setToken('abc')
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ ok: true }))
    const result = await makeClient(fetchImpl).get<{ ok: boolean }>('/tasks', { query: { status: ['A', 'B'], page: 2, skip: undefined } })
    expect(result).toEqual({ ok: true })
    const [url, init] = fetchImpl.mock.calls[0]!
    expect(url).toBe('/api/v1/tasks?status=A&status=B&page=2')
    expect((init!.headers as Record<string, string>).Authorization).toBe('Bearer abc')
  })

  it('gửi JSON body và Idempotency-Key (tự sinh hoặc truyền sẵn)', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(jsonResponse({ id: 1 })))
    const client = makeClient(fetchImpl)
    await client.post('/tasks', { title: 'T' }, { idempotencyKey: true })
    await client.post('/tasks', { title: 'T' }, { idempotencyKey: 'fixed-key' })
    const first = fetchImpl.mock.calls[0]![1]!
    const second = fetchImpl.mock.calls[1]![1]!
    expect(first.body).toBe(JSON.stringify({ title: 'T' }))
    expect((first.headers as Record<string, string>)['Content-Type']).toBe('application/json')
    expect((first.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)
    expect((second.headers as Record<string, string>)['Idempotency-Key']).toBe('fixed-key')
  })

  it('tự thêm Idempotency-Key cho endpoint hợp đồng yêu cầu; có thể tắt; GET không thêm', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(jsonResponse({})))
    const client = makeClient(fetchImpl)
    await client.post('/groups/g1/help-requests', {})
    await client.post('/groups/g1/help-requests', {}, { idempotencyKey: false })
    await client.post('/auth/login', {})
    const headersOf = (n: number) => fetchImpl.mock.calls[n]![1]!.headers as Record<string, string>
    expect(headersOf(0)['Idempotency-Key']).toBeTruthy()
    expect(headersOf(1)['Idempotency-Key']).toBeUndefined()
    expect(headersOf(2)['Idempotency-Key']).toBeUndefined()
  })

  it('trả undefined khi 204 không có thân', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }))
    await expect(makeClient(fetchImpl).delete('/tasks/1')).resolves.toBeUndefined()
  })

  it('parse lỗi chuẩn {code,message,fieldErrors,traceId} thành ApiError', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse(
        { code: 'VALIDATION_FAILED', message: 'Dữ liệu chưa hợp lệ', fieldErrors: [{ field: 'title', message: 'Bắt buộc' }], traceId: 't-1' },
        { status: 422 },
      ),
    )
    const error = await makeClient(fetchImpl).post('/tasks', {}).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError).toMatchObject({ status: 422, code: 'VALIDATION_FAILED', message: 'Dữ liệu chưa hợp lệ', traceId: 't-1' })
    expect(apiError.fieldErrors).toEqual([{ field: 'title', message: 'Bắt buộc' }])

    const setError = vi.fn()
    expect(applyFieldErrors(apiError, setError)).toBe(true)
    expect(setError).toHaveBeenCalledWith('title', { type: 'server', message: 'Bắt buộc' })
  })

  it('401 xóa token và phát sự kiện để về S00; có thể bỏ qua cho màn đăng nhập', async () => {
    setToken('expired')
    const listener = vi.fn()
    const off = onUnauthorized(listener)
    const fetchImpl = vi.fn<typeof fetch>().mockImplementation(() => Promise.resolve(jsonResponse({ code: 'UNAUTHORIZED', message: 'x' }, { status: 401 })))
    const client = makeClient(fetchImpl)

    await expect(client.get('/auth/me')).rejects.toMatchObject({ status: 401 })
    expect(listener).toHaveBeenCalledTimes(1)
    expect(getToken()).toBeNull()

    setToken('again')
    await expect(client.post('/auth/login', {}, { skipUnauthorizedHandler: true, anonymous: true })).rejects.toMatchObject({ status: 401 })
    expect(listener).toHaveBeenCalledTimes(1)
    expect(getToken()).toBe('again')
    off()
  })

  it('lỗi mạng → ApiError NETWORK_ERROR (status 0); phản hồi không phải JSON → mã HTTP_xxx', async () => {
    const failing = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(makeClient(failing).get('/x')).rejects.toMatchObject({ status: 0, code: CLIENT_ERROR.network })

    const html = vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502, statusText: 'Bad Gateway' }))
    await expect(makeClient(html).get('/x')).rejects.toMatchObject({ status: 502, code: 'HTTP_502', message: 'Bad Gateway' })
  })
})
