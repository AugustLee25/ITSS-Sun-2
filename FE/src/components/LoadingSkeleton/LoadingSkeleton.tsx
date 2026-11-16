import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import styles from './LoadingSkeleton.module.css'

export interface LoadingSkeletonProps {
  /** text: các dòng chữ; card: một thẻ; list: nhiều thẻ; block: khối chữ nhật tùy ý (đặt height qua className/style). */
  variant?: 'text' | 'card' | 'list' | 'block'
  lines?: number
  count?: number
  /** Nhãn đọc bởi screen reader. */
  label?: string
  className?: string
}

/** Skeleton cho thẻ/danh sách (spec 2.4 #1). Có vùng status để screen reader biết đang tải. */
export function LoadingSkeleton({ variant = 'text', lines = 3, count = 3, label, className }: LoadingSkeletonProps) {
  const t = useT()
  let content
  if (variant === 'text') {
    content = Array.from({ length: lines }, (_, i) => (
      <span key={i} className={cx(styles.bar, i === lines - 1 && lines > 1 && styles.short)} />
    ))
  } else if (variant === 'card') {
    content = <SkeletonCard />
  } else if (variant === 'list') {
    content = Array.from({ length: count }, (_, i) => <SkeletonCard key={i} />)
  } else {
    content = <span className={cx(styles.bar, styles.block)} />
  }
  return (
    <div role="status" aria-busy="true" aria-live="polite" className={cx(styles.root, variant === 'list' && styles.list, className)}>
      <span className="visually-hidden">{label ?? t('a11y.loadingRegion')}</span>
      <div aria-hidden="true" className={cx(styles.content, variant === 'list' && styles.list)}>
        {content}
      </div>
    </div>
  )
}

function SkeletonCard() {
  return (
    <div className={styles.card}>
      <span className={cx(styles.bar, styles.title)} />
      <span className={styles.bar} />
      <span className={cx(styles.bar, styles.short)} />
    </div>
  )
}
