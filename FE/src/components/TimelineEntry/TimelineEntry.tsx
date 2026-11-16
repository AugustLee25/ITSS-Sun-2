import type { ReactNode } from 'react'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { useFormatters } from '@/lib/timeZoneContext'
import { Icon, type IconName } from '../Icon/Icon'
import { TONE_ICONS, type StatusTone } from '../StatusChip/tones'
import styles from './TimelineEntry.module.css'

/** Danh sách mốc thời gian (lịch sử task, kế hoạch, bàn giao). Bọc các `TimelineEntry`. */
export function Timeline({ children, label, className }: { children: ReactNode; label?: string; className?: string }) {
  return (
    <ol className={cx(styles.list, className)} aria-label={label}>
      {children}
    </ol>
  )
}

export interface TimelineEntryProps {
  title: string
  /** ISO-8601 UTC. */
  at?: string | null
  /** Người thực hiện (hiển thị tên, minh bạch ai xác nhận — spec 8.3). */
  actorName?: string
  tone?: StatusTone
  icon?: IconName
  /** Nội dung phụ: lý do, so sánh cũ/mới, link minh chứng... */
  children?: ReactNode
}

export function TimelineEntry({ title, at, actorName, tone = 'neutral', icon, children }: TimelineEntryProps) {
  const t = useT()
  const f = useFormatters()
  return (
    <li className={styles.entry}>
      <span className={cx(styles.marker, styles[tone])}>
        <Icon name={icon ?? TONE_ICONS[tone]} size={14} />
      </span>
      <div className={styles.body}>
        <p className={styles.title}>{title}</p>
        {at || actorName ? (
          <p className={styles.meta}>
            {at ? <time dateTime={at}>{f.dateTime(at)}</time> : null}
            {at && actorName ? ' · ' : null}
            {actorName ? (
              <span>
                {t('fields.by')} {actorName}
              </span>
            ) : null}
          </p>
        ) : null}
        {children ? <div className={styles.content}>{children}</div> : null}
      </div>
    </li>
  )
}
