import { IDEMPOTENCY_RECOMMENDED, IDEMPOTENCY_REQUIRED } from './types'

/** "POST /plans/{planId}/review" → [method, regex khớp "/plans/<bất kỳ>/review"]. */
function compile(key: string): { method: string; pattern: RegExp } {
  const [method = '', template = ''] = key.split(' ')
  const source = template
    .split('/')
    .map((part) => (part.startsWith('{') ? '[^/]+' : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
    .join('/')
  return { method, pattern: new RegExp(`^${source}$`) }
}

const RULES = [...IDEMPOTENCY_REQUIRED, ...IDEMPOTENCY_RECOMMENDED].map(compile)

/** Endpoint (theo hợp đồng) cần/nên có `Idempotency-Key`: `ApiClient` tự thêm khóa cho các endpoint này. */
export function wantsIdempotencyKey(method: string, path: string): boolean {
  const clean = path.split('?')[0] ?? path
  const upper = method.toUpperCase()
  return RULES.some((rule) => rule.method === upper && rule.pattern.test(clean))
}
