import type { ReactNode } from 'react'
import { cx } from '@/lib/cx'
import styles from './Card.module.css'

export interface MetaItem {
  label: string
  value: ReactNode
}

/** Danh sách "nhãn: giá trị" gọn trong thẻ. Bỏ qua mục không có giá trị. */
export function MetaList({ items, className }: { items: Array<MetaItem | false | null | undefined>; className?: string }) {
  const visible = items.filter((item): item is MetaItem => !!item && item.value !== undefined && item.value !== null && item.value !== '')
  if (visible.length === 0) return null
  return (
    <dl className={cx(styles.meta, className)}>
      {visible.map((item) => (
        <div key={item.label} className={styles.metaItem}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}
