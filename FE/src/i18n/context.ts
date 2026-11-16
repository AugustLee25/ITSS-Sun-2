import { createContext } from 'react'
import { defaultT, type TFunction } from './translate'

export interface I18nValue {
  locale: string
  t: TFunction
}

export const I18nContext = createContext<I18nValue>({ locale: 'vi', t: defaultT })
