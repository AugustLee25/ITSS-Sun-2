import type { ReactNode } from 'react'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { Card, CardActions, CardChips, CardHead, PointsText, StretchedLink } from '../Card/Card'
import { MetaList } from '../Card/MetaList'
import { StatusChip } from '../StatusChip/StatusChip'
import type { ChipSpec } from '../StatusChip/presets'

export interface ContributionCardProps {
  title: string
  /** Liên kết tới chi tiết khoản điểm / yêu cầu. */
  href?: string
  taskTitle?: string
  /** Loại ghi nhận đã dịch, vd. "Hỗ trợ làm thay task". */
  kindLabel?: string
  /** Đã định dạng, vd. `+4,0000 điểm %`. */
  pointsLabel?: string
  confirmedBy?: string
  confirmedAt?: string | null
  status?: ChipSpec
  note?: string
  actions?: ReactNode
  className?: string
}

/** Một dòng trong sổ đóng góp (S12). Chỉ hiển thị đóng góp đã được xác nhận; không xếp hạng con người. */
export function ContributionCard({
  title,
  href,
  taskTitle,
  kindLabel,
  pointsLabel,
  confirmedBy,
  confirmedAt,
  status,
  note,
  actions,
  className,
}: ContributionCardProps) {
  const t = useT()
  const f = useFormatters()
  return (
    <Card as="article" interactive={!!href} accent={status?.tone === 'success' ? 'success' : 'none'} className={className}>
      <CardHead
        title={href ? <StretchedLink to={href}>{title}</StretchedLink> : title}
        status={pointsLabel ? <PointsText>{pointsLabel}</PointsText> : undefined}
      />
      <MetaList
        items={[
          kindLabel ? { label: t('fields.kind'), value: kindLabel } : null,
          taskTitle ? { label: t('fields.task'), value: taskTitle } : null,
          confirmedBy ? { label: t('fields.confirmedBy'), value: confirmedBy } : null,
          confirmedAt ? { label: t('fields.confirmedAt'), value: <time dateTime={confirmedAt}>{f.dateTime(confirmedAt)}</time> } : null,
          note ? { label: t('fields.note'), value: note } : null,
        ]}
      />
      {status ? (
        <CardChips>
          <StatusChip size="sm" {...status} />
        </CardChips>
      ) : null}
      {actions ? <CardActions>{actions}</CardActions> : null}
    </Card>
  )
}
