import type { ReactNode } from 'react'
import type { TaskSummary } from '@/api/types'
import { TaskTimeliness } from '@/api/types'
import { paths } from '@/app/routes'
import { Card, CardActions, CardChips, CardHead, MetaList, PointsText, StatusChip, StretchedLink, useStatusChip } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'

/**
 * Thẻ task dùng ở S03 (việc của tôi) và S04 (danh sách). Hai nhãn tách bạch: `Kế hoạch` và `Thực hiện` (spec S05);
 * mọi nhãn đúng hạn/trễ/điểm lấy nguyên văn từ server — FE không tự suy luận.
 */
export function TaskSummaryCard({
  task,
  groupId,
  showAssignee = true,
  actions,
}: {
  task: TaskSummary
  groupId: string
  showAssignee?: boolean
  actions?: ReactNode
}) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()

  const plan = chip('plan', task.planStatus)
  const execution = chip('task', task.taskStatus)
  const timeliness = chip('timeliness', task.timeliness)
  const risk = chip('risk', task.riskStatus)
  const takeover = task.originalAssignee.userId !== task.currentExecutor.userId

  return (
    <Card as="article" interactive accent={task.isLate || task.needsCarryover ? 'warning' : 'none'}>
      <CardHead
        title={<StretchedLink to={paths.task(groupId, task.id)}>{task.title}</StretchedLink>}
        status={<StatusChip tone={execution.tone} label={t('tasks.card.execution', { status: execution.label })} />}
      />
      <MetaList
        items={[
          showAssignee ? { label: t('fields.assignee'), value: task.currentExecutor.name } : null,
          { label: t('tasks.card.cycle'), value: t('tasks.card.cycleValue', { no: task.cycle.sequenceNo }) },
          task.startAt ? { label: t('tasks.card.start'), value: <time dateTime={task.startAt}>{f.dateTime(task.startAt)}</time> } : null,
          task.deadline ? { label: t('fields.deadline'), value: <time dateTime={task.deadline}>{f.dateTime(task.deadline)}</time> } : null,
          task.difficulty ? { label: t('fields.difficulty'), value: t(`status.difficulty.${task.difficulty}`) } : null,
          task.points ? { label: t('fields.points'), value: <PointsText>{f.pointsLabel(task.points.amount)}</PointsText> } : null,
        ]}
      />
      <CardChips>
        <StatusChip tone={plan.tone} size="sm" label={t('tasks.card.plan', { status: plan.label })} />
        {task.pendingRevisionStatus ? (
          <StatusChip
            tone={chip('plan', task.pendingRevisionStatus).tone}
            size="sm"
            label={t('tasks.card.pendingRevision', { status: chip('plan', task.pendingRevisionStatus).label })}
          />
        ) : null}
        {!task.committed && task.deadline ? <StatusChip tone="muted" size="sm" label={t('tasks.card.estimated')} /> : null}
        {task.timeliness !== TaskTimeliness.NOT_DUE ? <StatusChip tone={timeliness.tone} size="sm" label={task.timelinessLabel} /> : null}
        {task.riskStatus === 'AT_RISK' || task.riskStatus === 'BLOCKED' ? (
          <StatusChip tone={risk.tone} size="sm" label={t('tasks.card.risk', { status: risk.label })} />
        ) : null}
        {task.needsCarryover ? <StatusChip tone="warning" size="sm" label={t('tasks.card.carryover')} /> : null}
        {takeover ? (
          <StatusChip tone="info" size="sm" icon="users" label={t('tasks.card.takeover', { name: task.originalAssignee.name })} />
        ) : null}
      </CardChips>
      {actions ? <CardActions>{actions}</CardActions> : null}
    </Card>
  )
}
