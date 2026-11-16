import { useContext } from 'react'
import { I18nContext } from './context'

/** `const t = useT(); t('common.save'); t('copy.pointsRecorded', { points, task })` */
export function useT() {
  return useContext(I18nContext).t
}

export function useLocale() {
  return useContext(I18nContext).locale
}
