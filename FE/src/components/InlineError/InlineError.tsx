import { getErrorMessage, isApiError } from '@/api/errors'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { Button } from '../Button/Button'
import { Icon } from '../Icon/Icon'
import styles from './InlineError.module.css'

export interface InlineErrorProps {
  /** Thông điệp tự đặt; nếu không có sẽ suy ra từ `error`. */
  message?: string
  /** Lỗi gốc (ApiError...) — hiển thị thông điệp thân thiện và mã tham chiếu (traceId). */
  error?: unknown
  title?: string
  onRetry?: () => void
  retryLabel?: string
  tone?: 'error' | 'warning'
  className?: string
  id?: string
}

/** Lỗi cấp khối/biểu mẫu (role="alert"). Lỗi từng trường dùng `FormField error=`. */
export function InlineError({ message, error, title, onRetry, retryLabel, tone = 'error', className, id }: InlineErrorProps) {
  const t = useT()
  const text = message ?? getErrorMessage(error, t)
  const traceId = isApiError(error) ? error.traceId : undefined
  return (
    <div id={id} role="alert" className={cx(styles.box, tone === 'warning' && styles.warning, className)}>
      <Icon name={tone === 'warning' ? 'alert' : 'x-circle'} size={20} className={styles.icon} />
      <div className={styles.body}>
        {title ? <p className={styles.title}>{title}</p> : null}
        <p>{text}</p>
        {traceId ? <p className={styles.trace}>{t('states.traceId', { traceId })}</p> : null}
        {onRetry ? (
          <Button variant="secondary" icon="refresh" onClick={onRetry} className={styles.retry}>
            {retryLabel ?? t('common.retry')}
          </Button>
        ) : null}
      </div>
    </div>
  )
}
