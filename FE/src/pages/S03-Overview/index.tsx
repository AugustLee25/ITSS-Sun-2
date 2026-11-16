import { Link, useSearchParams } from 'react-router'
import { useDashboard } from '@/api/dashboard'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import { ButtonLink, Card, InlineError, LoadingSkeleton, MetaList, PageHeader, StatusChip, useStatusChip } from '@/components'
import { useT } from '@/i18n'
import { formatCalendarDate } from '@/lib/zonedTime'
import {
  ActionsBlock,
  ActivityBlock,
  CheckpointBlock,
  HelpBlock,
  MyTasksBlock,
  PointsBlock,
  RisksBlock,
} from './blocks'
import { LeadBlock } from './LeadBlock'
import { MembersBlock } from './MembersBlock'
import styles from './Overview.module.css'

/**
 * S03 — Tổng quan nhóm. Một request `GET /groups/{id}/dashboard` dựng cả trang; mọi màu/nhãn/lý do/đúng hạn do server tính.
 * Mobile: một cột theo thứ tự ưu tiên của spec; desktop: hai cột (Việc của tôi rộng hơn).
 */
export default function S03Overview() {
  const t = useT()
  const chip = useStatusChip()
  const { groupId, isLeader } = useGroup()
  const [params] = useSearchParams()
  const cycleId = params.get('cycleId')
  const query = useDashboard(groupId, cycleId)
  const data = query.data

  if (query.isPending) {
    return (
      <>
        <PageHeader title={t('screens.S03')} />
        <LoadingSkeleton variant="list" count={4} label={t('states.loading')} />
      </>
    )
  }
  if (query.isError || !data) {
    return (
      <>
        <PageHeader title={t('screens.S03')} />
        <InlineError error={query.error} title={t('overview.loadError')} onRetry={() => void query.refetch()} />
      </>
    )
  }

  const { group, cycle } = data
  const health = chip('groupHealth', group.health)
  const cycleLink = (id: string | null) => (id ? `${paths.group(groupId)}?cycleId=${encodeURIComponent(id)}` : null)
  const prev = cycleLink(data.previousCycleId)
  const next = cycleLink(data.nextCycleId)

  return (
    <>
      <PageHeader
        title={group.name}
        status={<StatusChip tone={health.tone} label={group.healthLabel} />}
        description={group.healthReason}
        actions={
          <ButtonLink to={paths.tasks(groupId)} variant="secondary" icon="tasks">
            {t('overview.viewTasks')}
          </ButtonLink>
        }
      />

      <Card as="section" aria-label={group.name} className={styles.summary}>
        <MetaList
          items={[
            group.courseName ? { label: t('overview.groupMeta.course'), value: group.courseName } : null,
            { label: t('overview.groupMeta.deadline'), value: <time dateTime={group.deadline}>{formatCalendarDate(group.deadline)}</time> },
            { label: t('overview.groupMeta.members'), value: group.memberCount },
            { label: t('overview.groupMeta.leader'), value: group.leader.name },
          ]}
        />
        <div className={styles.cycleBar}>
          <nav aria-label={t('overview.cycleNav')} className={styles.cycleNav}>
            {prev ? (
              <Link to={prev} className={styles.cycleLink}>
                {t('overview.prevCycle')}
              </Link>
            ) : null}
            <span className={styles.cycleCurrent}>
              <span className="visually-hidden">{t('overview.cycleTitle')}: </span>
              {cycle.label}
            </span>
            {next ? (
              <Link to={next} className={styles.cycleLink}>
                {t('overview.nextCycle')}
              </Link>
            ) : null}
          </nav>
          <StatusChip tone={chip('cyclePhase', cycle.phase).tone} size="sm" label={cycle.phaseLabel} />
          {!cycle.isCurrent ? (
            <Link to={paths.group(groupId)} className={styles.cycleLink}>
              {t('overview.notCurrentCycle')} {t('overview.backToCurrent')}
            </Link>
          ) : null}
        </div>
      </Card>

      <div className={styles.layout}>
        <div className={styles.areaActions}>
          <ActionsBlock actions={data.myActions} />
        </div>
        <div className={styles.areaTasks}>
          <MyTasksBlock groupId={groupId} tasks={data.myTasks} flags={data.flags} isLeader={isLeader} />
        </div>
        <div className={styles.areaCheckpoint}>
          <CheckpointBlock groupId={groupId} checkpoint={data.nextCheckpoint} />
        </div>
        <div className={styles.areaMembers}>
          <MembersBlock groupId={groupId} members={data.memberProgress} cycleId={cycleId} />
        </div>
        {data.leadPanel ? (
          <div className={styles.areaLead}>
            <LeadBlock groupId={groupId} panel={data.leadPanel} />
          </div>
        ) : null}
        <div className={styles.areaRisks}>
          <RisksBlock groupId={groupId} risks={data.sharedRisks} />
        </div>
        <div className={styles.areaHelp}>
          <HelpBlock groupId={groupId} help={data.openHelp} />
        </div>
        <div className={styles.areaPoints}>
          <PointsBlock groupId={groupId} points={data.myPoints} />
        </div>
        <div className={styles.areaActivity}>
          <ActivityBlock items={data.recentActivity} />
        </div>
      </div>
    </>
  )
}
