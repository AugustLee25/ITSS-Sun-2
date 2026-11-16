import type { ReactNode } from 'react'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { Card, CardActions, CardChips, CardHead, PointsText, StretchedLink, type CardProps } from '../Card/Card'
import { MetaList } from '../Card/MetaList'
import { StatusChip } from '../StatusChip/StatusChip'
import type { ChipSpec } from '../StatusChip/presets'

export interface TaskCardProps {
  title: string
  /** S05 của task; nếu có, toàn thẻ bấm được (một link cho screen reader). */
  href?: string
  assigneeName?: string
  /** ISO-8601 UTC; hiển thị `dd/MM/yyyy, HH:mm` theo múi giờ nhóm. */
  deadline?: string | null
  /** Trạng thái chính (kế hoạch / thực hiện / duyệt). */
  status?: ChipSpec
  /** Trạng thái phụ: "Chờ TL duyệt", "Trễ deadline"... */
  chips?: ChipSpec[]
  difficultyLabel?: string
  /** Đã định dạng bằng `formatPointsLabel`. */
  pointsLabel?: string
  accent?: CardProps['accent']
  /** Nút hành động (vd. "Cập nhật checkpoint"). */
  actions?: ReactNode
  className?: string
}

export function TaskCard({
  title,
  href,
  assigneeName,
  deadline,
  status,
  chips,
  difficultyLabel,
  pointsLabel,
  accent,
  actions,
  className,
}: TaskCardProps) {
  const t = useT()
  const f = useFormatters()
  return (
    <Card as="article" interactive={!!href} accent={accent} className={className}>
      <CardHead
        title={href ? <StretchedLink to={href}>{title}</StretchedLink> : title}
        status={status ? <StatusChip {...status} /> : undefined}
      />
      <MetaList
        items={[
          assigneeName ? { label: t('fields.assignee'), value: assigneeName } : null,
          deadline ? { label: t('fields.deadline'), value: <time dateTime={deadline}>{f.dateTime(deadline)}</time> } : null,
          difficultyLabel ? { label: t('fields.difficulty'), value: difficultyLabel } : null,
          pointsLabel ? { label: t('fields.points'), value: <PointsText>{pointsLabel}</PointsText> } : null,
        ]}
      />
      {chips && chips.length > 0 ? (
        <CardChips>
          {chips.map((chip) => (
            <StatusChip key={`${chip.tone}-${chip.label}`} size="sm" {...chip} />
          ))}
        </CardChips>
      ) : null}
      {actions ? <CardActions>{actions}</CardActions> : null}
    </Card>
  )
}
