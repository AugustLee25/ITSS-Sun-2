import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type {
  ActionItem,
  ActionKind,
  ActivityItem,
  DashboardPoints,
  DashboardResponse,
  HelpRequestCard as HelpCardData,
  NextCheckpointCard,
  SharedRiskCard,
  TaskSummary,
} from '@/api/types'
import { TaskAction } from '@/api/types'
import { paths } from '@/app/routes'
import {
  ButtonLink,
  Card,
  EmptyState,
  HelpRequestCard,
  Icon,
  MetaList,
  StatusChip,
  useStatusChip,
  type IconName,
} from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { cx } from '@/lib/cx'
import { formatDateTime } from '@/lib/format'
import { useFormatters } from '@/lib/timeZoneContext'
import { TaskSummaryCard } from '@/pages/S04-Tasks/TaskSummaryCard'
import { targetHref } from './targets'
import styles from './Overview.module.css'

export function Section({
  id,
  title,
  className,
  action,
  children,
}: {
  id: string
  title: string
  className?: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <Card as="section" aria-labelledby={id} className={cx(styles.section, className)}>
      <div className={styles.sectionHead}>
        <h2 id={id} className={styles.sectionTitle}>
          {title}
        </h2>
        {action}
      </div>
      {children}
    </Card>
  )
}

const ACTION_ICONS: Record<ActionKind, IconName> = {
  CHECKPOINT_REPORT: 'clock',
  COMPLETE_PLAN: 'edit',
  REVIEW_PLAN: 'file-text',
  REVIEW_RESULT: 'check',
  SET_CARRYOVER: 'calendar',
  FIX_TASK: 'alert',
  REVIEW_SUPPORT_REQUEST: 'help',
  REVIEW_SUPPORT_OFFERS: 'users',
  REVIEW_SUPPORT_RESULT: 'check',
  CONTINUE_SUPPORT_WORK: 'send',
}

/** 2. Khối hành động của tôi (quan trọng nhất): chỉ những CTA có thật do server trả. */
export function ActionsBlock({ actions }: { actions: ActionItem[] }) {
  const t = useT()
  const f = useFormatters()
  return (
    <Section id="ov-actions" title={t('overview.actionsTitle')} className={styles.primarySection}>
      {actions.length === 0 ? (
        <p className={styles.muted}>{t('overview.actionsEmpty')}</p>
      ) : (
        <ul className={styles.actionList}>
          {actions.map((action) => (
            <li key={`${action.kind}-${action.title}`} className={styles.actionItem}>
              <span className={styles.actionIcon} aria-hidden="true">
                <Icon name={ACTION_ICONS[action.kind]} size={22} />
              </span>
              <div className={styles.actionText}>
                <Link to={targetHref(action.target)} className={styles.actionLink}>
                  {action.title}
                </Link>
                {action.subtitle ? <p className={styles.muted}>{action.subtitle}</p> : null}
                {action.dueAt ? <p className={styles.muted}>{t('overview.actionDue', { time: f.dateTime(action.dueAt) })}</p> : null}
              </div>
              {action.count ? <StatusChip tone="info" size="sm" icon={false} label={t('overview.actionCount', { count: action.count })} /> : null}
              <Icon name="chevron-right" size={20} className={styles.chevron} />
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** 3. Công việc của tôi: 2–5 task sắp tới + empty state theo `flags`. */
export function MyTasksBlock({
  groupId,
  tasks,
  flags,
  isLeader,
}: {
  groupId: string
  tasks: TaskSummary[]
  flags: DashboardResponse['flags']
  isLeader: boolean
}) {
  const t = useT()
  return (
    <Section
      id="ov-tasks"
      title={t('overview.tasksTitle')}
      action={
        <ButtonLink to={paths.tasks(groupId)} variant="ghost" size="md">
          {t('overview.viewTasks')}
        </ButtonLink>
      }
    >
      {tasks.length === 0 ? (
        <EmptyState
          compact
          headingLevel={3}
          icon="tasks"
          title={flags.groupHasAnyTask ? t('overview.tasksEmptyMine') : t('overview.tasksEmptyGroup')}
          action={
            !flags.groupHasAnyTask ? (
              <ButtonLink to={paths.tasks(groupId)}>{isLeader ? t('overview.firstPlan') : t('overview.viewTasks')}</ButtonLink>
            ) : undefined
          }
        />
      ) : (
        <ul className={styles.stack}>
          {tasks.map((task) => (
            <li key={task.id}>
              <TaskSummaryCard
                task={task}
                groupId={groupId}
                showAssignee={false}
                actions={
                  <>
                    <ButtonLink to={paths.task(groupId, task.id)} variant="secondary">
                      {t('overview.viewTask')}
                    </ButtonLink>
                    {task.allowedActions.includes(TaskAction.REPORT_DIFFICULTY) ? (
                      <ButtonLink to={paths.taskDifficulty(groupId, task.id)} variant="ghost" preventScrollReset>
                        {t('overview.reportDifficulty')}
                      </ButtonLink>
                    ) : null}
                  </>
                }
              />
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** 4. Checkpoint tiếp theo: khung mở/chốt, múi giờ, giờ nhắc −7h; số task phải báo cáo, đã/chưa phản hồi. */
export function CheckpointBlock({ groupId, checkpoint }: { groupId: string; checkpoint: NextCheckpointCard | null }) {
  const t = useT()
  const chip = useStatusChip()
  const f = useFormatters()
  if (!checkpoint) {
    return (
      <Section id="ov-checkpoint" title={t('overview.checkpointTitle')}>
        <p className={styles.muted}>{t('overview.actionsEmpty')}</p>
      </Section>
    )
  }
  const { cycle } = checkpoint
  const report = chip('report', checkpoint.myReportState)
  return (
    <Section id="ov-checkpoint" title={t('overview.checkpointTitle')}>
      <p className={styles.cycleLabel}>{cycle.label}</p>
      <MetaList
        items={[
          { label: t('overview.checkpointOpens'), value: <time dateTime={cycle.opensAt}>{f.dateTime(cycle.opensAt)}</time> },
          { label: t('overview.checkpointCloses'), value: <time dateTime={cycle.closesAt}>{f.dateTime(cycle.closesAt)}</time> },
          { label: t('overview.checkpointReminder'), value: <time dateTime={cycle.reminderAt}>{f.dateTime(cycle.reminderAt)}</time> },
          { label: t('overview.checkpointTimezone'), value: cycle.timezone },
        ]}
      />
      <div className={styles.checkpointStats}>
        <p>
          <strong>{t('overview.checkpointMyTasks')}</strong>
          <br />
          {t('overview.checkpointMyProgress', { responded: checkpoint.myRespondedTaskCount, required: checkpoint.myRequiredTaskCount })}
        </p>
        <StatusChip tone={report.tone} label={checkpoint.myReportLabel || report.label} />
        <p>
          <strong>{t('overview.checkpointTeam')}</strong>
          <br />
          {t('overview.checkpointTeamLine', { reported: checkpoint.reportedMemberCount, missing: checkpoint.notRespondedMemberCount })}
        </p>
      </div>
      {checkpoint.myRequiredTaskCount === 0 ? (
        <p className={styles.muted}>{t('overview.checkpointNoRequired')}</p>
      ) : checkpoint.canReportNow ? (
        <ButtonLink to={paths.checkpoint(groupId, cycle.id)} preventScrollReset icon="clock">
          {t('overview.checkpointCta')}
        </ButtonLink>
      ) : (
        <p className={styles.muted}>{t('overview.checkpointNotOpen')}</p>
      )}
    </Section>
  )
}

/** 6. Việc có thể ảnh hưởng kế hoạch: chỉ phần được phép chia sẻ; không lộ nội dung hỗ trợ riêng. */
export function RisksBlock({ groupId, risks }: { groupId: string; risks: SharedRiskCard[] }) {
  const t = useT()
  return (
    <Section id="ov-risks" title={t('overview.risksTitle')}>
      {risks.length === 0 ? (
        <p className={styles.muted}>{t('overview.risksEmpty')}</p>
      ) : (
        <ul className={styles.stack}>
          {risks.map((risk) => (
            <li key={risk.id} className={styles.riskItem}>
              <StatusChip tone="warning" size="sm" label={t(`overview.riskTypes.${risk.riskType}` as MessageKey)} />
              <p>{risk.publicSummary}</p>
              {risk.taskTitle ? <p className={styles.muted}>{t('overview.riskTask', { task: risk.taskTitle })}</p> : null}
              {risk.taskId ? (
                <ButtonLink to={paths.task(groupId, risk.taskId)} variant="ghost">
                  {t('common.viewDetail')}
                </ButtonLink>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** 7a. Hỗ trợ đang mở (1–3 yêu cầu TL đăng, kèm độ khó và `+P_t điểm % khi TL duyệt`). */
export function HelpBlock({ groupId, help }: { groupId: string; help: HelpCardData[] }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  return (
    <Section
      id="ov-help"
      title={t('overview.helpTitle')}
      action={
        <ButtonLink to={paths.help(groupId)} variant="ghost">
          {t('overview.helpViewAll')}
        </ButtonLink>
      }
    >
      {help.length === 0 ? (
        <p className={styles.muted}>{t('overview.helpEmpty')}</p>
      ) : (
        <ul className={styles.stack}>
          {help.map((request) => (
            <li key={request.id}>
              <HelpRequestCard
                title={request.title}
                href={paths.helpRequest(groupId, request.id)}
                taskTitle={request.task.title}
                requesterName={request.postedBy.name}
                difficultyLabel={t(`status.difficulty.${request.difficulty}`)}
                pointsLabel={
                  request.points ? t('overview.helpWhenApproved', { points: f.pointsLabel(request.points, { signed: true }) }) : undefined
                }
                deadline={request.deadline}
                status={chip('help', request.status)}
              />
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** 7b. Điểm của tôi: `Đã xác nhận X% / Có thể đạt Y%` (nhãn do server dựng) + link S12. */
export function PointsBlock({ groupId, points }: { groupId: string; points: DashboardPoints }) {
  const t = useT()
  return (
    <Section id="ov-points" title={t('overview.pointsTitle')}>
      <p className={styles.pointsLabel}>{points.policyActive && points.label ? points.label : t('overview.pointsInactive')}</p>
      <ButtonLink to={paths.points(groupId)} variant="secondary" icon="award">
        {t('overview.pointsView')}
      </ButtonLink>
    </Section>
  )
}

/** 8. Hoạt động gần đây (tối đa 5, không phải feed dài). */
export function ActivityBlock({ items }: { items: ActivityItem[] }) {
  const t = useT()
  const f = useFormatters()
  return (
    <Section id="ov-activity" title={t('overview.activityTitle')}>
      {items.length === 0 ? (
        <p className={styles.muted}>{t('overview.activityEmpty')}</p>
      ) : (
        <ul className={styles.activityList}>
          {items.slice(0, 5).map((item) => (
            <li key={item.id}>
              <p>{item.target ? <Link to={targetHref(item.target)}>{item.text}</Link> : item.text}</p>
              <p className={styles.muted}>
                <time dateTime={item.at}>{f.relative(item.at)}</time>
                <span className="visually-hidden"> ({formatDateTime(item.at, f.timeZone)})</span>
              </p>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}
