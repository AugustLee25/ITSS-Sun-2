import { useEffect, useState } from 'react'
import { Navigate, useParams, useSearchParams } from 'react-router'
import { hasStatus } from '@/api/errors'
import { useTask } from '@/api/tasks'
import { TaskAction, TaskTimeliness, type TaskDetail } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { ForbiddenState } from '@/app/ErrorPages'
import { paths } from '@/app/routes'
import {
  Card,
  InlineError,
  LoadingSkeleton,
  MetaList,
  PageHeader,
  StatusChip,
  PointsText,
  useStatusChip,
  useToast,
} from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { CarryoverDialog } from './CarryoverDialog'
import { ExecutionActions } from './ExecutionActions'
import { HistorySection } from './HistorySection'
import { PlanSection } from './PlanSection'
import { IndependentReviewer, ReviewPanel } from './ReviewPanel'
import { SubmissionsSection } from './SubmissionsSection'
import styles from './TaskDetail.module.css'

/** Task không còn/không xem được: về danh sách S04 kèm thông báo (spec mục 5: "Task bị xóa → S04 + toast"). */
function TaskGone({ groupId }: { groupId: string }) {
  const t = useT()
  const toast = useToast()
  useEffect(() => {
    toast.info(t('taskDetail.gone'))
  }, [toast, t])
  return <Navigate to={paths.tasks(groupId)} replace />
}

function PointsBlock({ task }: { task: TaskDetail }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const points = task.points
  return (
    <Card as="section" aria-labelledby="points-title" className={styles.block}>
      <h2 id="points-title" className={styles.blockTitle}>
        {t('taskDetail.points.title')}
      </h2>
      {points ? (
        <>
          <p>
            <PointsText>{f.pointsLabel(points.amount)}</PointsText> <StatusChip {...chip('pointTask', points.status)} size="sm" />
          </p>
          <MetaList
            items={[
              { label: t('taskDetail.points.source'), value: points.originalAssignee.name },
              { label: t('taskDetail.points.recipient'), value: points.currentRecipient.name },
            ]}
          />
          {points.heldForHelpRequest ? <p className={styles.muted}>{t('taskDetail.points.held')}</p> : null}
          <p className={styles.muted}>{points.awarded ? t('taskDetail.points.awarded') : t('taskDetail.points.awaiting')}</p>
          <p className={styles.muted}>{t('taskDetail.points.unchangedByRound')}</p>
        </>
      ) : (
        <p className={styles.muted}>{t('taskDetail.points.none')}</p>
      )}
    </Card>
  )
}

function RoundsBlock({ task }: { task: TaskDetail }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  if (task.rounds.length < 2) return null
  return (
    <Card as="section" aria-labelledby="rounds-title" className={styles.block}>
      <h2 id="rounds-title" className={styles.blockTitle}>
        {t('taskDetail.rounds.title')}
      </h2>
      <ol className={styles.stack}>
        {task.rounds.map((round) => (
          <li key={round.id} className={styles.roundItem}>
            <div className={styles.blockHead}>
              <strong>{t('taskDetail.round', { no: round.roundNo })}</strong>
              <StatusChip {...chip('round', round.state)} size="sm" />
            </div>
            <p className={styles.muted}>
              {round.cycle.label}
              {round.deadline ? ` · ${t('fields.deadline')}: ${f.dateTime(round.deadline)}` : ''}
            </p>
            <p className={styles.muted}>{t(`taskDetail.rounds.reasons.${round.reason}` as MessageKey)}</p>
            {round.carryover ? (
              <p className={styles.muted}>
                {t('taskDetail.rounds.carriedBy', { name: round.carryover.decidedBy.name, time: f.dateTime(round.carryover.decidedAt) })}
                {round.carryover.decisionReason ? ` · ${t('taskDetail.rounds.carryReason', { reason: round.carryover.decisionReason })}` : ''}
              </p>
            ) : null}
          </li>
        ))}
      </ol>
    </Card>
  )
}

function InfoBlock({ task }: { task: TaskDetail }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const takeover = task.originalAssignee.userId !== task.currentExecutor.userId
  const timeliness = chip('timeliness', task.timeliness)
  const time = (iso: string) => <time dateTime={iso}>{f.dateTime(iso)}</time>
  return (
    <Card as="section" aria-labelledby="info-title" className={styles.block}>
      <h2 id="info-title" className={styles.blockTitle}>
        {t('taskDetail.infoTitle')}
      </h2>
      <MetaList
        className={styles.metaGrid}
        items={[
          { label: t('taskDetail.assignee'), value: task.currentExecutor.name },
          takeover ? { label: t('taskDetail.originalAssignee'), value: task.originalAssignee.name } : null,
          { label: t('taskDetail.cycle'), value: task.cycle.label },
          task.difficulty ? { label: t('fields.difficulty'), value: t(`status.difficulty.${task.difficulty}`) } : null,
          { label: t('taskDetail.round', { no: task.roundNo }), value: t(`status.round.${task.currentRound.state}`) },
        ]}
      />
      <h3 className={styles.subTitle}>{t('taskDetail.timelineTitle')}</h3>
      <MetaList
        className={styles.metaGrid}
        items={[
          task.startAt ? { label: t('taskDetail.startAt'), value: time(task.startAt) } : null,
          task.deadline
            ? { label: task.committed ? t('taskDetail.deadlineCurrent') : t('taskDetail.deadlineEstimated'), value: time(task.deadline) }
            : null,
          task.originalDeadline && task.originalDeadline !== task.deadline
            ? { label: t('taskDetail.deadlineOriginal'), value: time(task.originalDeadline) }
            : null,
          task.latestSubmittedAt ? { label: t('taskDetail.submittedAt'), value: time(task.latestSubmittedAt) } : null,
          task.acceptedAt ? { label: t('taskDetail.acceptedAt'), value: time(task.acceptedAt) } : null,
        ]}
      />
      {task.timeliness !== TaskTimeliness.NOT_DUE ? (
        <p>
          <StatusChip tone={timeliness.tone} label={task.timelinessLabel} />
        </p>
      ) : null}
      {task.completedLate ? <p className={styles.muted}>{t('taskDetail.completedLate')}</p> : null}

      <h3 className={styles.subTitle}>{t('taskDetail.scope')}</h3>
      <p className={styles.prewrap}>{task.description || t('taskDetail.notDescribed')}</p>
      <h3 className={styles.subTitle}>{t('taskDetail.output')}</h3>
      <p className={styles.prewrap}>{task.expectedOutput || t('taskDetail.notDescribed')}</p>

      {task.lastProgress ? (
        <>
          <h3 className={styles.subTitle}>{t('taskDetail.lastProgress')}</h3>
          <p className={styles.prewrap}>{task.lastProgress.note}</p>
          <p className={styles.muted}>
            {t('taskDetail.lastProgressBy', { name: task.lastProgress.by.name, time: f.dateTime(task.lastProgress.createdAt) })}
          </p>
        </>
      ) : null}
      <p className={styles.muted}>{t('taskDetail.privacy')}</p>
    </Card>
  )
}

/** S05 — Chi tiết công việc: hai nhãn kế hoạch/thực hiện, phiên bản kế hoạch, CTA theo `allowedActions`, kết quả, duyệt, lịch sử. */
export default function S05TaskDetail() {
  const t = useT()
  const chip = useStatusChip()
  const { taskId = '' } = useParams()
  const { groupId } = useGroup()
  const [params] = useSearchParams()
  const planId = params.get('planId')
  const query = useTask(taskId)
  const [carryoverOpen, setCarryoverOpen] = useState(false)
  const task = query.data

  const pendingId = task?.pendingRevision?.id
  useEffect(() => {
    if (planId && pendingId === planId) document.getElementById('plan-pending')?.scrollIntoView({ block: 'center' })
  }, [planId, pendingId])

  if (query.isPending) {
    return (
      <>
        <PageHeader title={t('screens.S05')} backTo={paths.tasks(groupId)} />
        <LoadingSkeleton variant="list" count={3} label={t('states.loading')} />
      </>
    )
  }
  if (query.isError) {
    if (hasStatus(query.error, 404)) return <TaskGone groupId={groupId} />
    if (hasStatus(query.error, 403)) return <ForbiddenState />
    return (
      <>
        <PageHeader title={t('screens.S05')} backTo={paths.tasks(groupId)} />
        <InlineError error={query.error} title={t('taskDetail.loadError')} onRetry={() => void query.refetch()} />
      </>
    )
  }
  if (!task || task.groupId !== groupId) return <TaskGone groupId={groupId} />

  const allowed = new Set(task.allowedActions)
  const plan = chip('plan', task.planStatus)
  const execution = chip('task', task.taskStatus)
  const reviewTarget = task.submissions.find((submission) => submission.isReviewTarget && !submission.review)

  return (
    <>
      <PageHeader
        title={task.title}
        breadcrumbs={[{ label: t('taskDetail.tasksCrumb'), to: paths.tasks(groupId) }, { label: task.title }]}
        backTo={paths.tasks(groupId)}
        status={
          <div className={styles.labels}>
            <StatusChip tone={plan.tone} label={t('tasks.card.plan', { status: plan.label })} />
            <StatusChip tone={execution.tone} label={t('tasks.card.execution', { status: execution.label })} />
          </div>
        }
      />

      <div className={styles.page}>
        <InfoBlock task={task} />
        <ExecutionActions task={task} onCarryover={() => setCarryoverOpen(true)} />
        {allowed.has(TaskAction.REVIEW_RESULT) && reviewTarget ? <ReviewPanel key={reviewTarget.id} task={task} target={reviewTarget} /> : null}
        {allowed.has(TaskAction.DESIGNATE_REVIEWER) ? (
          <Card as="section" className={styles.block}>
            <IndependentReviewer key={task.independentReviewer?.userId ?? 'none'} task={task} />
          </Card>
        ) : null}
        <PlanSection task={task} />
        <PointsBlock task={task} />
        <SubmissionsSection task={task} />
        <RoundsBlock task={task} />
        <HistorySection taskId={task.id} />
      </div>

      {carryoverOpen ? <CarryoverDialog task={task} onClose={() => setCarryoverOpen(false)} /> : null}
    </>
  )
}
