import { Link } from 'react-router'
import type { LeadPanel, ReviewQueueItem } from '@/api/types'
import { useReviewQueue } from '@/api/tasks'
import { paths } from '@/app/routes'
import { InlineError, LoadingSkeleton, StatusChip, useStatusChip } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { Section } from './blocks'
import styles from './Overview.module.css'

function QueueList({ groupId, items }: { groupId: string; items: ReviewQueueItem[] }) {
  const chip = useStatusChip()
  return (
    <ul className={styles.stack}>
      {items.map(({ row }) => (
        <li key={`${row.taskId}-${row.roundId}`} className={styles.queueItem}>
          <Link to={paths.task(groupId, row.taskId)} className={styles.actionLink}>
            {row.title}
          </Link>
          <p className={styles.muted}>{row.assignee.name}</p>
          <StatusChip tone={chip('timeliness', row.timeliness).tone} size="sm" label={row.timelinessLabel} />
        </li>
      ))}
    </ul>
  )
}

/** Khu dành cho TL: hàng đợi kế hoạch chờ duyệt, đề nghị hỗ trợ riêng, kiểm tra kỳ sau checkpoint (spec S03 #5). */
export function LeadBlock({ groupId, panel }: { groupId: string; panel: LeadPanel }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const review = panel.reviewCycle
  const queue = useReviewQueue(groupId, review?.cycleId, !!review)

  return (
    <Section id="ov-lead" title={t('overview.leadTitle')}>
      <div className={styles.leadGrid}>
        <div>
          <h3 className={styles.subTitle}>
            {t('overview.leadPlans')} ({panel.pendingPlans.count})
          </h3>
          {panel.pendingPlans.items.length === 0 ? (
            <p className={styles.muted}>{t('overview.leadPlansEmpty')}</p>
          ) : (
            <ul className={styles.stack}>
              {panel.pendingPlans.items.map((plan) => (
                <li key={plan.planId} className={styles.queueItem}>
                  <Link to={`${paths.task(groupId, plan.taskId)}?planId=${encodeURIComponent(plan.planId)}`} className={styles.actionLink}>
                    {plan.taskTitle}
                  </Link>
                  <StatusChip
                    tone={plan.isChange ? 'warning' : 'pending'}
                    size="sm"
                    label={plan.isChange ? t('overview.leadPlanChange') : t('overview.leadPlanNew')}
                  />
                  <p className={styles.muted}>{t('overview.leadPlanBy', { name: plan.assignee.name, time: f.dateTime(plan.submittedAt) })}</p>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <h3 className={styles.subTitle}>
            {t('overview.leadSupport')} ({panel.privateSupportRequests.count})
          </h3>
          {panel.privateSupportRequests.items.length === 0 ? (
            <p className={styles.muted}>{t('overview.leadSupportEmpty')}</p>
          ) : (
            <ul className={styles.stack}>
              {panel.privateSupportRequests.items.map((request) => (
                <li key={request.id} className={styles.queueItem}>
                  <Link to={paths.difficulty(groupId, request.id)} preventScrollReset className={styles.actionLink}>
                    {t('overview.leadSupportFrom', { name: request.sender.name, task: request.taskTitle })}
                  </Link>
                  <p className={styles.muted}>{f.relative(request.createdAt)}</p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {review ? (
        <div className={styles.reviewBox}>
          <h3 className={styles.subTitle}>{t('overview.leadReview')}</h3>
          <StatusChip tone={chip('cyclePhase', review.phase).tone} size="sm" label={chip('cyclePhase', review.phase).label} />
          <p>{review.headline}</p>
          {review.notRespondedMembers.length > 0 ? (
            <p className={styles.muted}>
              {t('overview.leadNotResponded', { names: review.notRespondedMembers.map((member) => member.name).join(', ') })}
            </p>
          ) : null}
          {queue.isPending ? <LoadingSkeleton variant="text" lines={2} /> : null}
          {queue.isError ? <InlineError error={queue.error} onRetry={() => void queue.refetch()} /> : null}
          {queue.data ? (
            <>
              {!queue.data.canReviewNow ? <p className={styles.muted}>{t('overview.leadReviewNotYet')}</p> : null}
              {queue.data.awaitingReview.length > 0 ? (
                <>
                  <h4>{t('overview.leadReviewAwaiting')}</h4>
                  <QueueList groupId={groupId} items={queue.data.awaitingReview} />
                </>
              ) : null}
              {queue.data.carryoverRequired.length > 0 ? (
                <>
                  <h4>{t('overview.leadReviewCarryover')}</h4>
                  <QueueList groupId={groupId} items={queue.data.carryoverRequired} />
                </>
              ) : null}
              {queue.data.awaitingReview.length === 0 && queue.data.carryoverRequired.length === 0 ? (
                <p className={styles.muted}>{t('overview.leadReviewNone')}</p>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </Section>
  )
}
