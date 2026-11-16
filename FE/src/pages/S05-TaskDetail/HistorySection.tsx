import { useState } from 'react'
import { useTaskHistory } from '@/api/tasks'
import type { TaskHistoryKind } from '@/api/types'
import { Button, Card, EmptyState, InlineError, LoadingSkeleton, Timeline, TimelineEntry, type StatusTone } from '@/components'
import { useT } from '@/i18n'
import styles from './TaskDetail.module.css'

const KIND_TONE: Record<TaskHistoryKind, StatusTone> = {
  TASK_CREATED: 'neutral',
  PLAN_SAVED: 'neutral',
  PLAN_SUBMITTED: 'pending',
  PLAN_WITHDRAWN: 'neutral',
  PLAN_APPROVED: 'success',
  PLAN_CHANGES_REQUESTED: 'warning',
  TASK_STARTED: 'info',
  PROGRESS_NOTE: 'info',
  RESULT_SUBMITTED: 'pending',
  RESULT_ACCEPTED: 'success',
  RESULT_FIX_REQUIRED: 'warning',
  RESULT_UPDATE_REQUIRED: 'warning',
  CARRIED_OVER: 'neutral',
  SUPPORT_HANDOFF: 'info',
  POINTS_AWARDED: 'success',
  POINTS_REVERSED: 'warning',
}

/** Lịch sử task (mới nhất trước); nội dung server dựng, không chứa trao đổi hỗ trợ riêng. */
export function HistorySection({ taskId }: { taskId: string }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [page, setPage] = useState(0)
  const history = useTaskHistory(taskId, page, open)
  const data = history.data

  return (
    <Card as="section" id="history" aria-labelledby="history-title" className={styles.block}>
      <div className={styles.blockHead}>
        <h2 id="history-title" className={styles.blockTitle}>
          {t('taskDetail.history.title')}
        </h2>
        <Button variant="secondary" aria-expanded={open} aria-controls="history-body" onClick={() => setOpen((value) => !value)}>
          {open ? t('taskDetail.hideHistory') : t('taskDetail.viewHistory')}
        </Button>
      </div>
      {open ? (
        <div id="history-body" className={styles.stack}>
          {history.isPending ? <LoadingSkeleton variant="text" lines={4} /> : null}
          {history.isError ? <InlineError error={history.error} title={t('taskDetail.history.loadError')} onRetry={() => void history.refetch()} /> : null}
          {data && data.items.length === 0 ? <EmptyState compact headingLevel={3} icon="inbox" title={t('taskDetail.history.empty')} /> : null}
          {data && data.items.length > 0 ? (
            <>
              <Timeline label={t('taskDetail.history.title')}>
                {data.items.map((entry) => (
                  <TimelineEntry key={entry.id} title={entry.summary} at={entry.at} actorName={entry.actor?.name} tone={KIND_TONE[entry.kind]}>
                    {entry.roundNo ? <span className={styles.muted}>{t('taskDetail.history.round', { no: entry.roundNo })}</span> : null}
                  </TimelineEntry>
                ))}
              </Timeline>
              {data.totalPages > 1 ? (
                <nav className={styles.pager} aria-label={t('taskDetail.history.title')}>
                  <Button variant="secondary" disabled={page <= 0} onClick={() => setPage(page - 1)}>
                    {t('tasks.pagePrev')}
                  </Button>
                  <span>{t('taskDetail.history.pageInfo', { page: data.page + 1, total: data.totalPages })}</span>
                  <Button variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
                    {t('tasks.pageNext')}
                  </Button>
                </nav>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
    </Card>
  )
}
