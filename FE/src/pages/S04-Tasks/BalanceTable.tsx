import { useState } from 'react'
import { usePointPolicy, usePointPolicyPreview } from '@/api/tasks'
import type { PointAllocation } from '@/api/types'
import { Button, Card, InlineError, LoadingSkeleton, StatusChip } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import styles from './Tasks.module.css'

function AllocationTable({ allocation }: { allocation: PointAllocation }) {
  const t = useT()
  const f = useFormatters()
  return (
    <table className={styles.balanceTable}>
      <thead>
        <tr>
          <th scope="col">{t('tasks.balance.member')}</th>
          <th scope="col">{t('tasks.balance.taskCount')}</th>
          <th scope="col">{t('tasks.balance.weightSum')}</th>
          <th scope="col">{t('tasks.balance.initial')}</th>
          <th scope="col">{t('tasks.balance.priced')}</th>
          <th scope="col">{t('tasks.balance.reserve')}</th>
        </tr>
      </thead>
      <tbody>
        {allocation.members.map((member) => (
          <tr key={member.user.userId}>
            <th scope="row" data-label={t('tasks.balance.member')}>
              {member.user.name}
              {member.warnings.map((warning) => (
                <span key={warning} className={styles.balanceWarning}>
                  {warning}
                </span>
              ))}
            </th>
            <td data-label={t('tasks.balance.taskCount')}>{member.taskCount}</td>
            <td data-label={t('tasks.balance.weightSum')}>{member.weightSum}</td>
            <td data-label={t('tasks.balance.initial')}>{f.pointsLabel(member.initialPoints)}</td>
            <td data-label={t('tasks.balance.priced')}>{f.pointsLabel(member.pricedPoints)}</td>
            <td data-label={t('tasks.balance.reserve')}>{f.pointsLabel(member.reservePoints)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * Bảng cân đối phân công cho TL (spec S04): tổng trọng số W_i và phần quỹ ban đầu B_i.
 * Số liệu do server tính (`point-policy` đã khóa, hoặc `preview` khi chưa khóa); FE chỉ hiển thị.
 */
export function BalanceTable({ groupId }: { groupId: string }) {
  const t = useT()
  const f = useFormatters()
  const [open, setOpen] = useState(false)
  const policy = usePointPolicy(groupId, open)
  const locked = policy.data?.active === true
  const preview = usePointPolicyPreview(groupId, open && policy.isSuccess && !locked)

  const allocation = locked ? policy.data?.allocation : preview.data?.allocation
  const loading = policy.isPending || (!locked && policy.isSuccess && preview.isPending)
  const error = policy.error ?? preview.error

  return (
    <Card as="section" aria-labelledby="balance-title" className={styles.balance}>
      <div className={styles.balanceHead}>
        <h2 id="balance-title" className={styles.balanceTitle}>
          {t('tasks.balance.title')}
        </h2>
        <Button variant="secondary" aria-expanded={open} aria-controls="balance-body" onClick={() => setOpen((value) => !value)}>
          {open ? t('tasks.balance.hide') : t('tasks.balance.show')}
        </Button>
      </div>
      <p className={styles.muted}>{t('tasks.balance.hint')}</p>
      {open ? (
        <div id="balance-body" className={styles.balanceBody}>
          {loading && !error ? <LoadingSkeleton variant="text" lines={4} /> : null}
          {error ? (
            <InlineError
              error={error}
              title={t('tasks.balance.loadError')}
              onRetry={() => {
                void policy.refetch()
                void preview.refetch()
              }}
            />
          ) : null}
          {policy.data ? (
            <p>
              {locked && policy.data.policy ? (
                <StatusChip tone="success" label={t('tasks.balance.locked', { no: policy.data.policy.versionNo })} />
              ) : (
                <StatusChip tone="muted" label={policy.data.statusLabel ?? t('tasks.balance.notLocked')} />
              )}
            </p>
          ) : null}
          {allocation ? (
            <>
              <AllocationTable allocation={allocation} />
              <p className={styles.muted}>{t('tasks.balance.unallocated', { points: f.pointsLabel(allocation.unallocatedPoints) })}</p>
            </>
          ) : null}
          {!locked && preview.data && preview.data.blockingIssues.length > 0 ? (
            <div>
              <h3 className={styles.balanceTitle}>{t('tasks.balance.issuesTitle')}</h3>
              <ul className={styles.issueList}>
                {preview.data.blockingIssues.map((issue) => (
                  <li key={`${issue.code}-${issue.taskId ?? issue.userId ?? issue.message}`}>{issue.message}</li>
                ))}
              </ul>
            </div>
          ) : null}
          {policy.data?.policy ? (
            <p className={styles.muted}>
              {t('tasks.balance.weights', {
                easy: policy.data.policy.weights.EASY,
                medium: policy.data.policy.weights.MEDIUM,
                hard: policy.data.policy.weights.HARD,
              })}
            </p>
          ) : null}
        </div>
      ) : null}
    </Card>
  )
}
