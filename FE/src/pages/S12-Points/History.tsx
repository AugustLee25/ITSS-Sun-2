import { useState } from 'react'
import { useLedgerEntry, useMyLedger } from '@/api/points'
import { LedgerEntryType, PointHistoryGroup, PointHistoryKind, PointTaskSource, type PointHistoryItem } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import {
  Button,
  ButtonLink,
  Card,
  CardActions,
  ContributionCard,
  EmptyState,
  FormField,
  InlineError,
  LoadingSkeleton,
  MetaList,
  Modal,
  PointsText,
  Select,
  StatusChip,
  useStatusChip,
} from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { EvidenceLinks } from '../S10-HelpExecution/WorkParts'
import { Pager } from './Pager'
import styles from './Points.module.css'

const entryHref = (groupId: string, id: string) => `${paths.points(groupId)}?tab=history&ledgerEntryId=${encodeURIComponent(id)}`

function Row({ item }: { item: PointHistoryItem }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const { groupId } = useGroup()
  const reversal = item.kind === PointHistoryKind.REVERSAL
  if (item.group === PointHistoryGroup.POINTS_RECORDED) {
    return (
      <ContributionCard
        title={item.label}
        href={item.ledgerEntryId ? entryHref(groupId, item.ledgerEntryId) : undefined}
        taskTitle={item.task?.title}
        kindLabel={reversal ? t('myPoints.history.reversal') : item.source ? t(`myPoints.tasks.${item.source}`) : undefined}
        pointsLabel={item.delta ? f.pointsLabel(item.delta) : undefined}
        confirmedBy={item.approvedBy?.name}
        confirmedAt={item.occurredAt}
        status={reversal ? { tone: 'warning', label: t('myPoints.history.reversal') } : chip('contribution', 'CONFIRMED')}
        note={[item.runningTotal ? t('myPoints.history.running', { points: f.pointsLabel(item.runningTotal) }) : null, item.reason ? t('myPoints.history.reason', { reason: item.reason }) : null]
          .filter(Boolean)
          .join(' · ')}
      />
    )
  }
  return (
    <Card as="article">
      <p>
        <strong>{item.label}</strong>
      </p>
      <p className={styles.muted}>
        <time dateTime={item.occurredAt}>{f.dateTime(item.occurredAt)}</time> · {t('myPoints.history.assignmentNote')}
      </p>
      <StatusChip size="sm" tone="neutral" label={t('myPoints.history.ASSIGNMENT')} />
      {item.workId ? (
        <CardActions>
          <ButtonLink variant="secondary" to={paths.helpWork(groupId, item.workId)}>
            {t('myPoints.tasks.openWork')}
          </ButtonLink>
        </CardActions>
      ) : null}
    </Card>
  )
}

/** Tab `Lịch sử đóng góp`: khoản điểm đã ghi tách khỏi sự kiện phân công (chuyển quyền không phải khoản trừ điểm). */
export function History() {
  const t = useT()
  const { groupId } = useGroup()
  const [group, setGroup] = useState<PointHistoryGroup | 'ALL'>('ALL')
  const [page, setPage] = useState(0)
  const query = useMyLedger(groupId, group, page, true)
  return (
    <section aria-labelledby="history-title" className={styles.block}>
      <h2 id="history-title" className={styles.blockTitle}>
        {t('myPoints.history.title')}
      </h2>
      <FormField label={t('myPoints.history.filter')} className={styles.filter}>
        <Select
          value={group}
          onChange={(event) => {
            setGroup(event.target.value as PointHistoryGroup | 'ALL')
            setPage(0)
          }}
        >
          {(['ALL', PointHistoryGroup.POINTS_RECORDED, PointHistoryGroup.ASSIGNMENT] as const).map((value) => (
            <option key={value} value={value}>
              {t(`myPoints.history.${value}`)}
            </option>
          ))}
        </Select>
      </FormField>
      {query.isPending ? <LoadingSkeleton variant="list" count={3} label={t('states.loading')} /> : null}
      {query.isError ? <InlineError error={query.error} title={t('myPoints.history.loadError')} onRetry={() => void query.refetch()} /> : null}
      {query.data && query.data.items.length === 0 ? (
        <EmptyState icon="coins" title={t('myPoints.history.empty')} description={t('myPoints.history.emptyHint')} />
      ) : null}
      {query.data && query.data.items.length > 0 ? (
        <div className={styles.list}>
          {query.data.items.map((item) => (
            <Row key={`${item.group}-${item.id}`} item={item} />
          ))}
        </div>
      ) : null}
      {query.data ? <Pager page={query.data.page} totalPages={query.data.totalPages} onChange={setPage} /> : null}
    </section>
  )
}

/** Chi tiết một khoản điểm: lần nộp, minh chứng, quyết định duyệt, đóng góp; đường tới S05 / S10. Mở bằng `?ledgerEntryId=`. */
export function LedgerDetail({ entryId, onClose }: { entryId: string; onClose: () => void }) {
  const t = useT()
  const f = useFormatters()
  const { groupId } = useGroup()
  const query = useLedgerEntry(groupId, entryId)
  const data = query.data
  return (
    <Modal
      open
      size="md"
      title={t('myPoints.entry.title')}
      onClose={onClose}
      footer={
        <Button variant="secondary" onClick={onClose}>
          {t('common.close')}
        </Button>
      }
    >
      {query.isPending ? <LoadingSkeleton variant="text" lines={4} label={t('states.loading')} /> : null}
      {query.isError ? <InlineError error={query.error} title={t('myPoints.entry.loadError')} onRetry={() => void query.refetch()} /> : null}
      {data ? (
        <div className={styles.block}>
          <p>
            <strong>{data.entry.label}</strong>
          </p>
          <MetaList
            items={[
              data.entry.delta ? { label: t('myPoints.entry.points'), value: <PointsText>{f.pointsLabel(data.entry.delta)}</PointsText> } : null,
              data.entry.runningTotal ? { label: t('myPoints.entry.running'), value: f.pointsLabel(data.entry.runningTotal) } : null,
              { label: t('fields.task'), value: data.task.title },
              { label: t('myPoints.entry.budget'), value: f.pointsLabel(data.budgetPoints) },
              { label: t('myPoints.entry.original'), value: data.originalAssignee.name },
              data.entry.source
                ? { label: t('myPoints.tasks.source'), value: t(data.entry.source === PointTaskSource.OWN ? 'myPoints.tasks.OWN' : 'myPoints.tasks.TAKEN_OVER') }
                : null,
              { label: t('myPoints.entry.approvedBy'), value: data.settlement.approvedBy.name },
              { label: t('myPoints.entry.approvedAt'), value: <time dateTime={data.settlement.approvedAt}>{f.dateTime(data.settlement.approvedAt)}</time> },
              data.review ? { label: t('myPoints.entry.decision'), value: t(`myPoints.entry.decisions.${data.review.decision}`) } : null,
              data.contribution
                ? { label: t('myPoints.entry.contribution'), value: t(`status.contribution.${data.contribution.status}`) }
                : null,
            ]}
          />
          {data.entry.reason ? <p>{t('myPoints.history.reason', { reason: data.entry.reason })}</p> : null}
          {data.entryType === LedgerEntryType.REVERSAL ? <p className={styles.muted}>{t('myPoints.entry.reverses')}</p> : null}
          {data.reversedByEntryId ? <p className={styles.muted}>{t('myPoints.entry.reversed')}</p> : null}
          {data.settlement.revokeReason ? <p className={styles.muted}>{t('myPoints.entry.revokeReason', { reason: data.settlement.revokeReason })}</p> : null}
          {data.submission ? (
            <>
              <h3 className={styles.subTitle}>
                {t('myPoints.entry.evidence')} · {t('myPoints.entry.submitted', { no: data.submission.submissionNo })}
              </h3>
              <EvidenceLinks items={data.submission.evidence} />
            </>
          ) : null}
          <div className={styles.row}>
            <ButtonLink variant="secondary" to={paths.task(groupId, data.task.id)}>
              {t('myPoints.entry.openTask')}
            </ButtonLink>
            {data.workId ? (
              <ButtonLink variant="secondary" to={paths.helpWork(groupId, data.workId)}>
                {t('myPoints.entry.openWork')}
              </ButtonLink>
            ) : null}
          </div>
        </div>
      ) : null}
    </Modal>
  )
}
