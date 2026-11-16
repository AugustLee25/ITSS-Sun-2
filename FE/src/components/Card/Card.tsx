import type { HTMLAttributes, ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'
import { cx } from '@/lib/cx'
import styles from './Card.module.css'

export interface CardProps extends HTMLAttributes<HTMLElement> {
  as?: 'div' | 'section' | 'article' | 'li'
  /** Thẻ có link kéo giãn (xem `StretchedLink`): hiển thị hover/focus trên cả thẻ. */
  interactive?: boolean
  /** Nhấn mạnh thẻ cần chú ý bằng viền trái (luôn kèm nội dung chữ ở trong thẻ). */
  accent?: 'none' | 'info' | 'success' | 'warning' | 'danger' | 'pending'
  children: ReactNode
}

export function Card({ as: Tag = 'div', interactive, accent = 'none', className, children, ...rest }: CardProps) {
  return (
    <Tag
      className={cx(styles.card, interactive && styles.interactive, accent !== 'none' && styles[`accent_${accent}`], className)}
      {...rest}
    >
      {children}
    </Tag>
  )
}

/** Link trong tiêu đề thẻ, phủ kín cả thẻ để vùng chạm lớn nhưng chỉ có MỘT link cho screen reader. */
export function StretchedLink({ className, ...rest }: LinkProps) {
  return <Link className={cx(styles.stretched, className)} {...rest} />
}

/** Vùng chứa hành động bên trong thẻ interactive (nổi lên trên link kéo giãn). */
export function CardActions({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cx(styles.actions, className)} {...rest} />
}

/** Hàng đầu thẻ: tiêu đề (h3) bên trái, trạng thái bên phải. */
export function CardHead({ title, status }: { title: ReactNode; status?: ReactNode }) {
  return (
    <div className={styles.head}>
      <h3 className={styles.headTitle}>{title}</h3>
      {status}
    </div>
  )
}

/** Hàng chip trạng thái phụ. */
export function CardChips({ children }: { children: ReactNode }) {
  return <div className={styles.chips}>{children}</div>
}

/** Số điểm nổi bật (dạng số đồng đều, nhãn đã kèm đơn vị "điểm %"). */
export function PointsText({ children }: { children: ReactNode }) {
  return <span className={styles.points}>{children}</span>
}
