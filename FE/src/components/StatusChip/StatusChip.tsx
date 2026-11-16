import { cx } from '@/lib/cx'
import { Icon, type IconName } from '../Icon/Icon'
import { TONE_ICONS, type StatusTone } from './tones'
import styles from './StatusChip.module.css'

export interface StatusChipProps {
  tone: StatusTone
  /** Nhãn chữ bắt buộc (lấy từ i18n). */
  label: string
  /** Ghi đè biểu tượng; `false` để ẩn. */
  icon?: IconName | false
  size?: 'sm' | 'md'
  className?: string
}

export function StatusChip({ tone, label, icon, size = 'md', className }: StatusChipProps) {
  const iconName = icon === undefined ? TONE_ICONS[tone] : icon
  return (
    <span className={cx(styles.chip, styles[tone], size === 'sm' && styles.sm, className)} data-tone={tone}>
      {iconName ? <Icon name={iconName} size={size === 'sm' ? 14 : 16} /> : null}
      <span>{label}</span>
    </span>
  )
}
