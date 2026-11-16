import { useCallback } from 'react'
import { useT } from '@/i18n'
import { statusChip, type ChipSpec, type StatusDomain, type StatusValue } from './presets'

/** `const chip = useStatusChip(); <StatusChip {...chip('task', task.status)} />` */
export function useStatusChip() {
  const t = useT()
  return useCallback(<D extends StatusDomain>(domain: D, value: StatusValue<D>): ChipSpec => statusChip(t, domain, value), [t])
}
