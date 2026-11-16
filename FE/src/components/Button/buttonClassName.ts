import { cx } from '@/lib/cx'
import styles from './Button.module.css'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'md' | 'lg'

export function buttonClassName({ variant = 'primary', size = 'md', fullWidth }: { variant?: ButtonVariant; size?: ButtonSize; fullWidth?: boolean }, extra?: string) {
  return cx(styles.button, styles[variant], size === 'lg' && styles.lg, fullWidth && styles.full, extra)
}

