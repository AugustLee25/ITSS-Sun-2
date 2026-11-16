import { Link, useSearchParams } from 'react-router'
import { useMemberProgress } from '@/api/dashboard'
import type { CycleTaskRow, MemberProgress } from '@/api/types'
import { paths } from '@/app/routes'
import { useCurrentUser } from '@/app/auth/authContext'
import { BottomSheet, EmptyState, InlineError, LoadingSkeleton, MemberStatusFrame, StatusChip, useStatusChip } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { Section } from './blocks'
import styles from './Overview.module.css'

/** Dòng tóm tắt số liệu của khung tên: số task đến hạn/hoàn thành/trễ/thiếu báo cáo (spec 0.6, S03 #5). */
function MemberDetailLine({ progress }: { progress: MemberProgress }) {
  const t = useT()
  const { evaluation } = progress
  const counts = evaluation.counts
  return (
    <span className={styles.frameDetail}>
      <span>{evaluation.reason}</span>
      <span>
        {t('overview.memberCounts', {
          due: counts.dueTasks,
          accepted: counts.acceptedTasks,
          late: counts.lateTasks,
          missing: counts.missingReportTasks,
        })}
      </span>
      <span>
        {evaluation.phaseLabel} · {progress.reportLabel}
      </span>
    </span>
  )
}

function TaskRowView({ groupId, row }: { groupId: string; row: CycleTaskRow }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const timeliness = chip('timeliness', row.timeliness)
  return (
    <li className={styles.sheetRow}>
      <Link to={paths.task(groupId, row.taskId)} className={styles.actionLink}>
        {row.title}
      </Link>
      <p className={styles.muted}>
        {t('fields.deadline')}: <time dateTime={row.deadline}>{f.dateTime(row.deadline)}</time>
        {row.handedOverTo ? ` · ${t('tasks.card.takeover', { name: row.assignee.name })} (${row.handedOverTo.name})` : ''}
      </p>
      <div className={styles.chipRow}>
        <StatusChip tone={timeliness.tone} size="sm" label={row.timelinessLabel} />
        <StatusChip tone={chip('task', row.taskStatus).tone} size="sm" label={chip('task', row.taskStatus).label} />
      </div>
      {row.submittedAt ? (
        <p className={styles.muted}>{t('overview.memberSubmittedAt', { time: f.dateTime(row.submittedAt) })}</p>
      ) : null}
      {row.review ? (
        <p className={styles.muted}>
          {t('overview.memberReviewed', { decision: t(`overview.reviewDecision.${row.review.decision}` as MessageKey) })}
          {row.review.issueDescription ? ` — ${row.review.issueDescription}` : ''}
        </p>
      ) : null}
      {row.reportNote ? <p className={styles.muted}>{t('overview.memberNote', { note: row.reportNote })}</p> : null}
    </li>
  )
}

function MemberSheet({ groupId, userId, cycleId, onClose }: { groupId: string; userId: string; cycleId: string | null; onClose: () => void }) {
  const t = useT()
  const f = useFormatters()
  const query = useMemberProgress(groupId, userId, cycleId)
  const detail = query.data
  const evaluation = detail?.progress.evaluation
  return (
    <BottomSheet
      open
      onClose={onClose}
      title={detail ? t('overview.memberSheetTitle', { name: detail.progress.member.name }) : t('overview.membersTitle')}
      description={detail ? t('overview.memberSheetCycle', { label: detail.cycle.label }) : undefined}
    >
      {query.isPending ? <LoadingSkeleton variant="list" count={2} /> : null}
      {query.isError ? <InlineError error={query.error} onRetry={() => void query.refetch()} /> : null}
      {detail && evaluation ? (
        <div className={styles.sheetBody}>
          <div className={styles.chipRow}>
            <StatusChip
              tone={
                evaluation.color === 'GREEN' ? 'success' : evaluation.color === 'YELLOW' ? 'warning' : evaluation.color === 'RED' ? 'danger' : 'muted'
              }
              label={evaluation.statusLabel}
            />
            <StatusChip tone="neutral" size="sm" label={evaluation.phaseLabel} />
          </div>
          <p>{evaluation.reason}</p>
          {detail.progress.cutoffEvaluation ? (
            <p className={styles.muted}>{t('overview.memberAtCutoff', { status: detail.progress.cutoffEvaluation.statusLabel })}</p>
          ) : null}
          <p className={styles.muted}>
            {t('overview.memberReportState')}: {detail.progress.reportLabel}
          </p>
          {detail.progress.dropoutRisk ? (
            <p className={styles.muted}>
              {detail.progress.dropoutRisk.publicSummary} —{' '}
              {t('overview.memberDropout', {
                name: detail.progress.dropoutRisk.confirmedBy.name,
                time: f.dateTime(detail.progress.dropoutRisk.confirmedAt),
              })}
            </p>
          ) : null}
          {detail.tasks.length === 0 ? (
            <EmptyState compact headingLevel={3} icon="tasks" title={t('overview.memberSheetEmpty')} />
          ) : (
            <ul className={styles.stack}>
              {detail.tasks.map((row) => (
                <TaskRowView key={`${row.taskId}-${row.roundId}`} groupId={groupId} row={row} />
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </BottomSheet>
  )
}

/** 5. Tiến độ thành viên: khung tên có màu + nhãn chữ + lý do/số task; chạm để xem từng task. */
export function MembersBlock({ groupId, members, cycleId }: { groupId: string; members: MemberProgress[]; cycleId: string | null }) {
  const t = useT()
  const me = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const selected = params.get('member')

  function open(userId: string | null) {
    const next = new URLSearchParams(params)
    if (userId) next.set('member', userId)
    else next.delete('member')
    setParams(next, { preventScrollReset: true })
  }

  return (
    <Section id="ov-members" title={t('overview.membersTitle')}>
      <p className={styles.muted}>{t('overview.membersHint')}</p>
      <ul className={styles.memberGrid}>
        {members.map((progress) => (
          <li key={progress.member.userId}>
            <MemberStatusFrame
              name={progress.member.name}
              color={progress.evaluation.color}
              label={progress.evaluation.statusLabel}
              detail={<MemberDetailLine progress={progress} />}
              provisional={progress.evaluation.provisional}
              pendingCount={progress.evaluation.counts.pendingReviewTasks}
              isSelf={progress.member.userId === me.userId}
              onClick={() => open(progress.member.userId)}
            />
          </li>
        ))}
      </ul>
      {selected ? <MemberSheet groupId={groupId} userId={selected} cycleId={cycleId} onClose={() => open(null)} /> : null}
    </Section>
  )
}
