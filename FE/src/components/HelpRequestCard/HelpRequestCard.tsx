import type { ReactNode } from 'react'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { Card, CardActions, CardChips, CardHead, PointsText, StretchedLink } from '../Card/Card'
import { MetaList, type MetaItem } from '../Card/MetaList'
import { StatusChip } from '../StatusChip/StatusChip'
import type { ChipSpec } from '../StatusChip/presets'

export interface HelpRequestCardProps {
  /** Tiêu đề yêu cầu / phần việc cần hỗ trợ. */
  title: string
  /** S09. */
  href?: string
  taskTitle?: string
  requesterName?: string
  difficultyLabel?: string
  /** Điểm sẽ chuyển khi TL duyệt, vd. `+10,0000 điểm %` (đã định dạng). */
  pointsLabel?: string
  effortLabel?: string
  deadline?: string | null
  status?: ChipSpec
  chips?: ChipSpec[]
  offersCount?: number
  /** Thông tin thêm (vd. mô tả tóm tắt, đầu ra cần bàn giao). */
  extraMeta?: MetaItem[]
  actions?: ReactNode
  className?: string
}

/** Thẻ yêu cầu hỗ trợ công khai ở bảng hỗ trợ S08. Hỗ trợ là tự nguyện: không dùng ngôn ngữ ép buộc. */
export function HelpRequestCard({
  title,
  href,
  taskTitle,
  requesterName,
  difficultyLabel,
  pointsLabel,
  effortLabel,
  deadline,
  status,
  chips,
  offersCount,
  extraMeta,
  actions,
  className,
}: HelpRequestCardProps) {
  const t = useT()
  const f = useFormatters()
  return (
    <Card as="article" interactive={!!href} className={className}>
      <CardHead
        title={href ? <StretchedLink to={href}>{title}</StretchedLink> : title}
        status={status ? <StatusChip {...status} /> : undefined}
      />
      <MetaList
        items={[
          taskTitle ? { label: t('fields.task'), value: taskTitle } : null,
          requesterName ? { label: t('fields.requester'), value: requesterName } : null,
          difficultyLabel ? { label: t('fields.difficulty'), value: difficultyLabel } : null,
          effortLabel ? { label: t('fields.effort'), value: effortLabel } : null,
          deadline ? { label: t('fields.deadline'), value: <time dateTime={deadline}>{f.dateTime(deadline)}</time> } : null,
          pointsLabel ? { label: t('fields.points'), value: <PointsText>{pointsLabel}</PointsText> } : null,
          offersCount !== undefined ? { label: t('fields.offers'), value: offersCount } : null,
          ...(extraMeta ?? []),
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
