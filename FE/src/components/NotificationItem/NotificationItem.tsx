import { Link } from 'react-router'
import { NotificationPriority, type NotificationType } from '@/api/types'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { formatDateTime, formatRelativeTime, type DateInput } from '@/lib/format'
import { useTimeZone } from '@/lib/timeZoneContext'
import { Icon } from '../Icon/Icon'
import { NOTIFICATION_ICONS } from './notificationTypes'
import styles from './NotificationItem.module.css'

export interface NotificationItemProps {
  type: NotificationType
  /** Tiêu đề ngắn do BE dựng sẵn (`NotificationItem.title`); mặc định theo loại (i18n). */
  title?: string
  /** Ngữ cảnh: "Nhóm Lập trình Web · Task T" (`groupName` + `subjectLabel`). Không chứa nội dung hỗ trợ riêng. */
  context?: string
  /** `NotificationItem.priority`: ACTION_REQUIRED được nhấn mạnh (biểu tượng xanh); còn lại trung tính. */
  priority?: NotificationPriority
  createdAt: string
  read: boolean
  /** Đích đã kiểm tra quyền (S05/S06/S09/...). Không có → hiển thị dạng nút. */
  href?: string
  /** Gọi khi nhấn: dùng để đánh dấu đã đọc. */
  onActivate?: () => void
  /** CTA ngữ cảnh; mặc định theo loại. */
  actionLabel?: string
  /** Thời điểm hiện tại (cho test/kit). */
  now?: DateInput
}

/** Một dòng thông báo (S13): biểu tượng loại việc, tiêu đề, ngữ cảnh, thời gian tương đối, đã đọc/chưa đọc, CTA. */
export function NotificationItem({ type, title, context, priority, createdAt, read, href, onActivate, actionLabel, now }: NotificationItemProps) {
  const t = useT()
  const timeZone = useTimeZone()
  const heading = title ?? t(`notifications.types.${type}.title`)
  const cta = actionLabel ?? t(`notifications.types.${type}.cta`)
  const urgent = priority === NotificationPriority.ACTION_REQUIRED

  const content = (
    <>
      <span className={cx(styles.icon, urgent && styles.urgent)}>
        <Icon name={NOTIFICATION_ICONS[type]} size={20} />
      </span>
      <span className={styles.body}>
        <span className={styles.title}>
          {!read ? <span className="visually-hidden">{t('notifications.unread')}: </span> : null}
          {heading}
        </span>
        {context ? <span className={styles.context}>{context}</span> : null}
        <span className={styles.footer}>
          <time dateTime={createdAt} title={formatDateTime(createdAt, timeZone)} className={styles.time}>
            {formatRelativeTime(createdAt, { now, timeZone })}
          </time>
          <span className={styles.cta}>
            {cta}
            <Icon name="chevron-right" size={16} />
          </span>
        </span>
      </span>
      {!read ? <span className={styles.dot} aria-hidden="true" /> : null}
    </>
  )

  const className = cx(styles.item, !read && styles.unread)
  if (href) {
    return (
      <Link to={href} className={className} onClick={onActivate}>
        {content}
      </Link>
    )
  }
  return (
    <button type="button" className={className} onClick={onActivate}>
      {content}
    </button>
  )
}
