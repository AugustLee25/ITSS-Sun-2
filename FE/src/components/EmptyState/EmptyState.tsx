import type { ReactNode } from 'react'
import { cx } from '@/lib/cx'
import { Icon, type IconName } from '../Icon/Icon'
import styles from './EmptyState.module.css'

export interface EmptyStateProps {
  /** Icon nhỏ minh họa (spec 2.4 #2). */
  icon?: IconName
  title: string
  description?: string
  /** Đúng MỘT CTA: `<Button>` hoặc `<ButtonLink>`. */
  action?: ReactNode
  /** Gọn hơn, dùng trong thẻ/khối nhỏ. */
  compact?: boolean
  className?: string
  /** Mức heading của tiêu đề (mặc định h2). */
  headingLevel?: 2 | 3
}

export function EmptyState({ icon = 'inbox', title, description, action, compact, className, headingLevel = 2 }: EmptyStateProps) {
  const Heading = headingLevel === 3 ? 'h3' : 'h2'
  return (
    <div className={cx(styles.root, compact && styles.compact, className)}>
      <span className={styles.icon}>
        <Icon name={icon} size={compact ? 22 : 28} />
      </span>
      <Heading className={styles.title}>{title}</Heading>
      {description ? <p className={styles.description}>{description}</p> : null}
      {action ? <div className={styles.action}>{action}</div> : null}
    </div>
  )
}
