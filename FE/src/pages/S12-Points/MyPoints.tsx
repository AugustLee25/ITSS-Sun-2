import { useState } from 'react'
import { useMyPointTasks, useMyPoints } from '@/api/points'
import { PointTaskSource, PointTaskStatus, type MyPointsResponse } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import {
  ButtonLink,
  Card,
  CardActions,
  CardChips,
  CardHead,
  EmptyState,
  InlineError,
  LoadingSkeleton,
  MetaList,
  PointsText,
  StatusChip,
  StretchedLink,
  useStatusChip,
} from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { Pager } from './Pager'
import { Simulation } from './Simulation'
import styles from './Points.module.css'

function Summary({ data }: { data: MyPointsResponse }) {
  const t = useT()
  const f = useFormatters()
  const showHeld = data.pendingTransfer.openRequestCount > 0 || data.pendingTransfer.heldPoints.pointUnits > 0
  return (
    <Card as="section" aria-labelledby="my-points-title" className={styles.block}>
      <h2 id="my-points-title" className={styles.blockTitle}>
        {t('myPoints.summary.title')}
      </h2>
      <dl className={styles.stats}>
        {data.initialPoints ? (
          <div className={`${styles.stat} ${styles.toneInitial}`}>
            <dt>{t('myPoints.summary.initial')}</dt>
            <dd className={styles.statValue}>
              {data.memberCount
                ? t('myPoints.summary.initialValue', { points: f.pointsLabel(data.initialPoints), count: data.memberCount })
                : f.pointsLabel(data.initialPoints)}
            </dd>
            <dd className={styles.muted}>{t('myPoints.summary.initialHint')}</dd>
          </div>
        ) : null}
        <div className={`${styles.stat} ${styles.toneConfirmed}`}>
          <dt>{t('myPoints.summary.confirmed')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.confirmedPoints)}</dd>
          <dd className={styles.muted}>
            {data.confirmedPoints.pointUnits === 0 ? t('myPoints.summary.confirmedZero') : t('myPoints.summary.confirmedHint')}
          </dd>
        </div>
        <div className={`${styles.stat} ${styles.tonePending}`}>
          <dt>{t('myPoints.summary.pending')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.pendingPoints)}</dd>
          <dd className={styles.muted}>{t('myPoints.summary.pendingHint')}</dd>
        </div>
        <div className={`${styles.stat} ${styles.tonePotential}`}>
          <dt>{t('myPoints.summary.potential')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.potentialPoints)}</dd>
          <dd className={styles.muted}>{t('myPoints.summary.potentialHint')}</dd>
        </div>
      </dl>
      {data.goalCompletionPercentLabel ? (
        <p>
          {t('myPoints.summary.goal', { percent: data.goalCompletionPercentLabel })}
          <span className={styles.muted}> — {t('myPoints.summary.goalHint')}</span>
        </p>
      ) : null}
      <dl className={styles.breakdown}>
        <div>
          <dt>{t('myPoints.summary.transferred')}</dt>
          <dd>
            {t('myPoints.summary.transferredValue', { points: f.pointsLabel(data.transferredOut.points), count: data.transferredOut.taskCount })}
          </dd>
        </div>
        <div>
          <dt>{t('myPoints.summary.takenOver')}</dt>
          <dd>{t('myPoints.summary.takenOverValue', { points: f.pointsLabel(data.takenOver.points), count: data.takenOver.taskCount })}</dd>
        </div>
        {showHeld ? (
          <div>
            <dt>{t('myPoints.summary.held')}</dt>
            <dd>
              {t('myPoints.summary.heldValue', {
                points: f.pointsLabel(data.pendingTransfer.heldPoints),
                count: data.pendingTransfer.openRequestCount,
              })}
            </dd>
          </div>
        ) : null}
        {data.unallocatedReservePoints.pointUnits > 0 ? (
          <div>
            <dt>{t('myPoints.summary.reserve')}</dt>
            <dd>{f.pointsLabel(data.unallocatedReservePoints)}</dd>
          </div>
        ) : null}
      </dl>
      <p className={styles.muted}>{t('myPoints.summary.breakdownHint')}</p>
    </Card>
  )
}

function PointTasks() {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const { groupId } = useGroup()
  const [page, setPage] = useState(0)
  const query = useMyPointTasks(groupId, page, true)
  return (
    <section aria-labelledby="point-tasks-title" className={styles.block}>
      <h2 id="point-tasks-title" className={styles.blockTitle}>
        {t('myPoints.tasks.title')}
      </h2>
      {query.isPending ? <LoadingSkeleton variant="list" count={2} label={t('states.loading')} /> : null}
      {query.isError ? <InlineError error={query.error} title={t('myPoints.tasks.loadError')} onRetry={() => void query.refetch()} /> : null}
      {query.data && query.data.items.length === 0 ? (
        <EmptyState compact icon="coins" headingLevel={3} title={t('myPoints.tasks.empty')} description={t('myPoints.tasks.emptyHint')} />
      ) : null}
      {query.data && query.data.items.length > 0 ? (
        <ul className={styles.list}>
          {query.data.items.map((row) => (
            <Card as="li" key={row.taskId} interactive accent={row.status === PointTaskStatus.AWARDED ? 'success' : 'none'}>
              <CardHead
                title={<StretchedLink to={paths.task(groupId, row.taskId)}>{row.title}</StretchedLink>}
                status={<PointsText>{f.pointsLabel(row.points)}</PointsText>}
              />
              <MetaList
                items={[
                  { label: t('fields.difficulty'), value: t(`status.difficulty.${row.difficulty}`) },
                  { label: t('myPoints.tasks.source'), value: t(row.source === PointTaskSource.OWN ? 'myPoints.tasks.OWN' : 'myPoints.tasks.TAKEN_OVER') },
                  { label: t('myPoints.tasks.original'), value: row.originalAssignee.name },
                  { label: t('myPoints.tasks.recipient'), value: row.currentRecipient.name },
                  row.cycle ? { label: t('myPoints.tasks.cycle'), value: row.cycle.label } : null,
                  row.deadline ? { label: t('fields.deadline'), value: <time dateTime={row.deadline}>{f.dateTime(row.deadline)}</time> } : null,
                ]}
              />
              <CardChips>
                <StatusChip size="sm" {...chip('pointTask', row.status)} />
                {row.heldForRequest ? <StatusChip size="sm" tone="pending" label={t('myPoints.tasks.held')} /> : null}
              </CardChips>
              {row.transferredTo ? <p className={styles.muted}>{t('myPoints.tasks.transferredTo', { name: row.transferredTo.name })}</p> : null}
              {row.helpRequestId && row.workId ? (
                <CardActions>
                  <ButtonLink variant="secondary" to={paths.helpWork(groupId, row.helpRequestId)}>
                    {t('myPoints.tasks.openWork')}
                  </ButtonLink>
                </CardActions>
              ) : null}
            </Card>
          ))}
        </ul>
      ) : null}
      {query.data ? <Pager page={query.data.page} totalPages={query.data.totalPages} onChange={setPage} /> : null}
    </section>
  )
}

/** Tab `Điểm của tôi`: lỗi mạng → thử lại (không thay bằng 0); chưa khóa phương án → `Chưa có phương án điểm đã duyệt`. */
export function MyPoints() {
  const t = useT()
  const { groupId } = useGroup()
  const query = useMyPoints(groupId)
  if (query.isPending) return <LoadingSkeleton variant="list" count={2} label={t('states.loading')} />
  if (query.isError) return <InlineError error={query.error} title={t('myPoints.loadError')} onRetry={() => void query.refetch()} />
  const data = query.data
  if (!data.policyActive) {
    return <EmptyState icon="coins" title={data.statusLabel ?? t('myPoints.noPolicy.title')} description={t('myPoints.noPolicy.description')} />
  }
  return (
    <>
      <Summary data={data} />
      <PointTasks />
      <Simulation />
    </>
  )
}
