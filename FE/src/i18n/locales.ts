import { vi, type Messages } from './vi'

export type Locale = 'vi' | 'ja'

/** Thêm ngôn ngữ mới: tạo `ja.ts` (kiểu `Messages`) rồi đăng ký ở đây. */
export const locales: Record<Locale, Messages | undefined> = {
  vi,
  ja: undefined,
}

export const DEFAULT_LOCALE: Locale = 'vi'
