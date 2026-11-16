import type { ReactNode } from 'react'
import { useT } from '@/i18n'
import { Card } from '../Card/Card'
import { Icon } from '../Icon/Icon'
import styles from './CheckpointPrompt.module.css'

export interface CheckpointPromptProps {
  /** Mặc định: "Đến lúc cập nhật ngắn về công việc rồi!" (spec 7.2). */
  title?: string
  /** Ví dụ: "Giờ chốt: 15/11/2026, 17:00". */
  description?: string
  /** Một CTA mở S06 của đúng kỳ. */
  action?: ReactNode
  /** Đã cập nhật: hiện lời xác nhận thay vì lời nhắc. */
  done?: boolean
}

/** Lời nhắc checkpoint trung lập, không đổ lỗi (spec 7.2). */
export function CheckpointPrompt({ title, description, action, done }: CheckpointPromptProps) {
  const t = useT()
  return (
    <Card as="section" accent={done ? 'success' : 'info'} aria-label={t('copy.checkpointNow')}>
      <div className={styles.row}>
        <span className={styles.icon}>
          <Icon name={done ? 'check' : 'clock'} size={22} />
        </span>
        <div className={styles.text}>
          <p className={styles.title}>{done ? t('copy.checkpointDone') : (title ?? t('copy.checkpointNow'))}</p>
          {description ? <p className={styles.description}>{description}</p> : null}
        </div>
        {action && !done ? <div className={styles.action}>{action}</div> : null}
      </div>
    </Card>
  )
}
