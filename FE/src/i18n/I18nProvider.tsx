import { useMemo, type ReactNode } from 'react'
import { I18nContext } from './context'
import { DEFAULT_LOCALE, locales, type Locale } from './locales'
import { createT } from './translate'
import { vi } from './vi'

export function I18nProvider({ locale = DEFAULT_LOCALE, children }: { locale?: Locale; children: ReactNode }) {
  const value = useMemo(() => ({ locale, t: createT(locales[locale] ?? vi) }), [locale])
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}
