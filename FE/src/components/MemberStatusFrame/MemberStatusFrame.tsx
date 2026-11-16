import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { DashboardColor, DashboardReasonCode } from '@/api/types'
import { cx } from '@/lib/cx'
import { useT } from '@/i18n'
import { Icon, type IconName } from '../Icon/Icon'
import { MemberAvatar } from '../MemberAvatar/MemberAvatar'
import { StatusChip } from '../StatusChip/StatusChip'
import styles from './MemberStatusFrame.module.css'

/** Ký hiệu khác nhau cho từng màu để không chỉ dựa vào màu (✓ ! ✕ –). */
const FRAME_ICON: Record<DashboardColor, IconName> = {
  GREEN: 'check',
  YELLOW: 'alert',
  RED: 'x-circle',
  GRAY: 'minus-circle',
}

export interface MemberStatusFrameProps {
  name: string
  avatarSrc?: string | null
  /** Màu khung tên (mục 0.6) do SERVER tính (`DashboardColor`); FE chỉ hiển thị. */
  color: DashboardColor
  /** Mã lý do của server: dùng làm nhãn mặc định ("Trễ 1–2 task", "Đã xác nhận hoàn thành"...). */
  reason?: DashboardReasonCode
  /** Nhãn chữ tự đặt (ưu tiên hơn `reason`), vd. "Trễ 2 task" kèm số lượng. */
  label?: string
  /** Lý do/số lượng đi kèm (spec 0.6: "Màu luôn có nhãn chữ, số lượng và lý do"). */
  detail?: ReactNode
  /** Kỳ chưa chốt: hiện chip "Tạm tính". */
  provisional?: boolean
  /** Số task chờ TL duyệt (hiện cạnh nhãn). */
  pendingCount?: number
  isSelf?: boolean
  /** Click khung tên mở danh sách task, deadline, trạng thái nộp/duyệt. */
  onClick?: () => void
  href?: string
  className?: string
}

export function MemberStatusFrame({
  name,
  avatarSrc,
  color,
  reason,
  label,
  detail,
  provisional,
  pendingCount,
  isSelf,
  onClick,
  href,
  className,
}: MemberStatusFrameProps) {
  const t = useT()
  const text = label ?? (reason ? t(`status.reason.${reason}`) : t(`status.frame.${color.toLowerCase() as Lowercase<DashboardColor>}`))
  const body = (
    <>
      <MemberAvatar name={name} src={avatarSrc} decorative />
      <span className={styles.text}>
        <span className={styles.name}>
          {name}
          {isSelf ? <span className={styles.self}> ({t('common.you')})</span> : null}
        </span>
        <span className={styles.label}>
          <Icon name={FRAME_ICON[color]} size={16} />
          <span>{text}</span>
        </span>
        {detail ? <span className={styles.detail}>{detail}</span> : null}
        {provisional || pendingCount ? (
          <span className={styles.meta}>
            {provisional ? <StatusChip tone="neutral" size="sm" icon="clock" label={t('status.cyclePhase.TENTATIVE')} /> : null}
            {pendingCount ? (
              <StatusChip tone="pending" size="sm" label={t('status.frame.pendingCount', { count: pendingCount })} />
            ) : null}
          </span>
        ) : null}
      </span>
    </>
  )
  const cls = cx(styles.frame, styles[color.toLowerCase()], (onClick || href) && styles.interactive, className)
  if (href) {
    return (
      <Link to={href} className={cls} data-color={color}>
        {body}
      </Link>
    )
  }
  if (onClick) {
    return (
      <button type="button" className={cls} data-color={color} onClick={onClick}>
        {body}
      </button>
    )
  }
  return (
    <div className={cls} data-color={color}>
      {body}
    </div>
  )
}
