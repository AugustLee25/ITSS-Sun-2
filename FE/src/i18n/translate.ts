import { vi, type Messages } from './vi'

type Paths<T, P extends string = ''> = T extends string
  ? P
  : { [K in keyof T & string]: Paths<T[K], P extends '' ? K : `${P}.${K}`> }[keyof T & string]

/** Khóa chuỗi hợp lệ dạng `nhóm.khóa` (kiểm tra lúc biên dịch). */
export type MessageKey = Paths<Messages>
export type TParams = Record<string, string | number>
export type TFunction = (key: MessageKey, params?: TParams) => string

function lookup(messages: Messages, key: string): string | undefined {
  let node: unknown = messages
  for (const part of key.split('.')) {
    if (node === null || typeof node !== 'object') return undefined
    node = (node as Record<string, unknown>)[part]
  }
  return typeof node === 'string' ? node : undefined
}

export function interpolate(template: string, params?: TParams): string {
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) => (name in params ? String(params[name]) : match))
}

/** Tạo hàm `t` cho một bộ thông điệp; thiếu khóa thì rơi về tiếng Việt, rồi về chính khóa. */
export function createT(messages: Messages): TFunction {
  return (key, params) => interpolate(lookup(messages, key) ?? lookup(vi, key) ?? key, params)
}

export const defaultT: TFunction = createT(vi)
