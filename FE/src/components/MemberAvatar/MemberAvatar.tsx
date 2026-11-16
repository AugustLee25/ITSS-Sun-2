import { cx } from '@/lib/cx'
import { getInitials } from './initials'
import styles from './MemberAvatar.module.css'

export interface MemberAvatarProps {
  name: string
  src?: string | null
  size?: 'sm' | 'md' | 'lg'
  /** Đặt true khi tên đã hiển thị bên cạnh để screen reader không đọc lặp. */
  decorative?: boolean
  className?: string
}

const PALETTE = 6

function hash(text: string): number {
  let h = 0
  for (const ch of text) h = (h * 31 + (ch.codePointAt(0) ?? 0)) % 997
  return h
}

export function MemberAvatar({ name, src, size = 'md', decorative, className }: MemberAvatarProps) {
  const a11y = decorative ? { 'aria-hidden': true } : { role: 'img', 'aria-label': name }
  return (
    <span className={cx(styles.avatar, styles[size], styles[`c${hash(name) % PALETTE}`], className)} {...a11y}>
      {src ? <img src={src} alt="" className={styles.img} /> : getInitials(name).toUpperCase()}
    </span>
  )
}
