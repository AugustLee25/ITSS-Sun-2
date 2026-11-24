import { Link } from 'react-router'
import { useMemberTaskBoard } from '@/api/dashboard'
import { MemberTaskSection, type MemberTaskBoardRow } from '@/api/types'
import { paths } from '@/app/routes'
import { ButtonLink, EmptyState, InlineError, LoadingSkeleton, StatusChip, useStatusChip } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { cx } from '@/lib/cx'
import { useFormatters } from '@/lib/timeZoneContext'
import styles from './Overview.module.css'

/** `Đưa lên Workspace` mở form đăng ở S08 với task (và đề nghị riêng nếu có) đã chọn sẵn; nội dung lấy từ task gốc. */
function publishHref(groupId: string, row: MemberTaskBoardRow): string {
  const q = new URLSearchParams({ create: '1', taskId: row.task.id })
  if (row.supportRequestId) q.set('supportRequestId', row.supportRequestId)
  return `${paths.help(groupId)}?${q.toString()}`
}

const KNOWN_SECTIONS: string[] = Object.values(MemberTaskSection)

/** T-06: section lạ do BE thêm sau này không được lộ khóa i18n thô — dùng nhãn chung "Khác". */
function sectionLabel(t: ReturnType<typeof useT>, section: string): string {
  return t((KNOWN_SECTIONS.includes(section) ? `overview.board.sections.${section}` : 'overview.board.sections.OTHER') as MessageKey)
}

function BoardRow({ groupId, row }: { groupId: string; row: MemberTaskBoardRow }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const { task } = row
  // Quá hạn = đỏ pastel; cần hỗ trợ = vàng pastel. Vừa quá hạn vừa cần hỗ trợ: một dòng, đỏ + thêm nhãn `Cần hỗ trợ`.
  const pastel = row.section === MemberTaskSection.OVERDUE ? 'red' : row.section === MemberTaskSection.NEEDS_HELP ? 'yellow' : undefined
  const takeover = task.originalAssignee.userId !== task.currentExecutor.userId
  return (
    <li
      className={cx(styles.boardRow, pastel === 'red' && styles.rowRed, pastel === 'yellow' && styles.rowYellow)}
      data-section={row.section}
      data-overdue={row.overdue}
      data-needs-help={row.needsHelp}
    >
      <Link to={paths.task(groupId, task.id)} className={cx(styles.boardTitle, pastel === 'red' && styles.titleRed, pastel === 'yellow' && styles.titleYellow)} data-pastel={pastel}>
        {task.title}
      </Link>
      <div className={styles.chipRow}>
        {row.overdue ? <StatusChip tone="danger" size="sm" label={t('overview.board.overdue')} /> : null}
        {row.needsHelp ? <StatusChip tone="warning" size="sm" label={t('overview.board.needsHelp')} /> : null}
        {row.awaitingReview ? <StatusChip tone="pending" size="sm" label={t('overview.board.awaitingReview')} /> : null}
        {row.done ? <StatusChip tone="success" size="sm" label={t('overview.board.done')} /> : null}
        {row.helpRequestId ? <StatusChip tone="info" size="sm" label={t('overview.board.published')} /> : null}
        {takeover ? <StatusChip tone="info" size="sm" icon="users" label={t('tasks.card.takeover', { name: task.originalAssignee.name })} /> : null}
        {!row.done && !row.awaitingReview ? <StatusChip tone={chip('task', task.taskStatus).tone} size="sm" label={chip('task', task.taskStatus).label} /> : null}
      </div>
      {task.deadline ? (
        <p className={styles.muted}>
          {t('fields.deadline')}: <time dateTime={task.deadline}>{f.dateTime(task.deadline)}</time>
        </p>
      ) : null}
      <div className={styles.boardActions}>
        {row.supportRequestId && row.needsHelp ? (
          <ButtonLink variant="ghost" to={paths.difficulty(groupId, row.supportRequestId)} preventScrollReset>
            {t('overview.board.viewRequest')}
          </ButtonLink>
        ) : null}
        {row.canPublishToWorkspace ? (
          <ButtonLink variant="secondary" to={publishHref(groupId, row)} data-testid={`publish-${task.id}`}>
            {t('overview.board.publish')}
          </ButtonLink>
        ) : !row.done && row.publishBlockedMessage ? (
          <p className={styles.muted}>{t('overview.board.blocked', { reason: row.publishBlockedMessage })}</p>
        ) : null}
      </div>
    </li>
  )
}

/**
 * Solution 2 · L1 — TL bấm một Member để xem TOÀN BỘ task đang phụ trách (trong tuần, tồn đọng, đã hoàn thành).
 * Thứ tự và phân nhóm do server quyết định (quá hạn → cần hỗ trợ → đang làm → chờ duyệt → đã xong); FE giữ nguyên thứ tự đó.
 */
export function MemberTaskBoard({ groupId, userId }: { groupId: string; userId: string }) {
  const t = useT()
  const query = useMemberTaskBoard(groupId, userId)
  if (query.isPending) return <LoadingSkeleton variant="list" count={2} />
  if (query.isError) return <InlineError error={query.error} title={t('overview.board.loadFailed')} onRetry={() => void query.refetch()} />
  const board = query.data
  if (board.tasks.length === 0) return <EmptyState compact headingLevel={3} icon="tasks" title={t('overview.board.empty')} />

  // nhóm các dòng liền kề cùng section (server đã sắp xếp theo section) để có tiêu đề nhóm, không sắp xếp lại ở FE
  const groups: Array<{ section: MemberTaskSection; rows: MemberTaskBoardRow[] }> = []
  for (const row of board.tasks) {
    const last = groups[groups.length - 1]
    if (last && last.section === row.section) last.rows.push(row)
    else groups.push({ section: row.section, rows: [row] })
  }
  return (
    <div data-testid="member-task-board">
      <p className={styles.muted}>{t('overview.board.hint')}</p>
      <p className={styles.muted}>
        {t('overview.board.counts', {
          overdue: board.counts.OVERDUE,
          needsHelp: board.counts.NEEDS_HELP,
          awaiting: board.counts.AWAITING_REVIEW,
          done: board.counts.DONE,
        })}
      </p>
      {groups.map((group) => (
        <section key={group.section} aria-label={sectionLabel(t, group.section)} className={styles.boardGroup}>
          <h3 className={styles.boardHeading}>
            {sectionLabel(t, group.section)} ({group.rows.length})
          </h3>
          <ul className={styles.stack}>
            {group.rows.map((row) => (
              <BoardRow key={row.task.id + '-' + row.task.roundId} groupId={groupId} row={row} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

