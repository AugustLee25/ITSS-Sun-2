import { Link, useSearchParams } from 'react-router'
import { useGroupPointsSummary } from '@/api/points'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import { Card, EmptyState, InlineError, LoadingSkeleton, PageHeader } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { History, LedgerDetail } from './History'
import { MyPoints } from './MyPoints'
import styles from './Points.module.css'

const TABS = ['mine', 'history', 'fund'] as const
type Tab = (typeof TABS)[number]

/** Tab `Quỹ điểm nhóm`: X + Y + Z = 100% quỹ, không chuẩn hóa lại X, không xếp hạng thành viên. */
function GroupFund() {
  const t = useT()
  const f = useFormatters()
  const { groupId } = useGroup()
  const query = useGroupPointsSummary(groupId, true)
  if (query.isPending) return <LoadingSkeleton variant="card" label={t('states.loading')} />
  if (query.isError) return <InlineError error={query.error} title={t('myPoints.fund.loadError')} onRetry={() => void query.refetch()} />
  const data = query.data
  if (!data.policyActive) {
    return <EmptyState icon="coins" title={data.statusLabel ?? t('myPoints.noPolicy.title')} description={t('myPoints.noPolicy.description')} />
  }
  return (
    <Card as="section" aria-labelledby="fund-title" className={styles.block}>
      <h2 id="fund-title" className={styles.blockTitle}>
        {t('myPoints.fund.title')}
      </h2>
      <dl className={styles.stats}>
        <div className={`${styles.stat} ${styles.toneInitial}`}>
          <dt>{t('myPoints.fund.total')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.totalPoints)}</dd>
        </div>
        <div className={`${styles.stat} ${styles.toneConfirmed}`}>
          <dt>{t('myPoints.fund.confirmed')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.confirmedPoints)}</dd>
        </div>
        <div className={`${styles.stat} ${styles.tonePending}`}>
          <dt>{t('myPoints.fund.pending')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.pendingPoints)}</dd>
        </div>
        <div className={`${styles.stat} ${styles.tonePotential}`}>
          <dt>{t('myPoints.fund.unallocated')}</dt>
          <dd className={styles.statValue}>{f.pointsLabel(data.unallocatedPoints)}</dd>
        </div>
      </dl>
      <p>{t('myPoints.fund.sum')}</p>
      <p className={styles.muted}>{t('myPoints.fund.hint')}</p>
    </Card>
  )
}

/** S12 — Điểm của tôi & Sổ đóng góp. Link riêng: `?tab=mine|history|fund`, `?ledgerEntryId=` mở chi tiết khoản điểm, `?taskId=` chọn sẵn task mô phỏng. */
export default function S12Points() {
  const t = useT()
  const { groupId } = useGroup()
  const [params, setParams] = useSearchParams()
  const entryId = params.get('ledgerEntryId')
  const requested = params.get('tab')
  const tab: Tab = TABS.find((value) => value === requested) ?? (entryId ? 'history' : 'mine')

  const closeEntry = () =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.delete('ledgerEntryId')
        return next
      },
      { replace: true, preventScrollReset: true },
    )

  return (
    <>
      <PageHeader title={t('screens.S12')} backTo={paths.group(groupId)} />
      <div className={styles.page}>
        <nav className={styles.tabs} aria-label={t('myPoints.tabsLabel')}>
          {TABS.map((value) => (
            <Link
              key={value}
              className={styles.tab}
              to={{ search: `?tab=${value}` }}
              replace
              preventScrollReset
              aria-current={tab === value ? 'page' : undefined}
            >
              {t(`myPoints.tabs.${value}`)}
            </Link>
          ))}
        </nav>
        {tab === 'mine' ? <MyPoints /> : tab === 'history' ? <History /> : <GroupFund />}
      </div>
      {entryId ? <LedgerDetail entryId={entryId} onClose={closeEntry} /> : null}
    </>
  )
}
