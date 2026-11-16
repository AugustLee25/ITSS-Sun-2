import { useState } from 'react'
import { useRoundSubmissions } from '@/api/tasks'
import type { TaskDetail, TaskRound, TaskSubmission } from '@/api/types'
import { Button, Card, InlineError, LoadingSkeleton, StatusChip } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import styles from './TaskDetail.module.css'

function SubmissionView({ submission }: { submission: TaskSubmission }) {
  const t = useT()
  const f = useFormatters()
  return (
    <li className={styles.submission}>
      <div className={styles.blockHead}>
        <h3 className={styles.subTitle}>{t('taskDetail.results.no', { no: submission.submissionNo })}</h3>
        <div className={styles.chipRow}>
          <StatusChip tone={submission.isOnTime ? 'success' : 'warning'} size="sm" label={submission.isOnTime ? t('taskDetail.results.onTime') : t('taskDetail.results.late')} />
          {submission.isReviewTarget ? <StatusChip tone="pending" size="sm" label={t('taskDetail.results.target')} /> : null}
        </div>
      </div>
      <p className={styles.muted}>
        {t('taskDetail.results.by', { name: submission.submittedBy.name, time: f.dateTime(submission.submittedAt) })} ·{' '}
        {t('taskDetail.results.deadlineAt', { time: f.dateTime(submission.deadline) })}
      </p>
      {submission.resultNote ? (
        <p>
          <strong>{t('taskDetail.results.note')}:</strong> {submission.resultNote}
        </p>
      ) : null}
      <ul className={styles.evidenceList} aria-label={t('taskDetail.results.evidence')}>
        {submission.evidence.map((item) => (
          <li key={item.url}>
            <a href={item.url} target="_blank" rel="noopener noreferrer">
              {item.label || item.url}
            </a>
          </li>
        ))}
      </ul>
      {submission.review ? (
        <p className={styles.reviewLine}>
          {t('taskDetail.results.reviewed', {
            decision: t(`overview.reviewDecision.${submission.review.decision}` as MessageKey),
            name: submission.review.reviewer.name,
            time: f.dateTime(submission.review.reviewedAt),
          })}
          {submission.review.issueDescription ? (
            <>
              <br />
              <strong>{t('taskDetail.results.issue')}:</strong> {submission.review.issueDescription}
            </>
          ) : null}
        </p>
      ) : null}
    </li>
  )
}

function PreviousRound({ round }: { round: TaskRound }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const query = useRoundSubmissions(round.id, open)
  return (
    <div>
      <Button variant="ghost" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        {t('taskDetail.results.loadRound', { no: round.roundNo })}
      </Button>
      {open ? (
        query.isPending ? (
          <LoadingSkeleton variant="text" lines={2} />
        ) : query.isError ? (
          <InlineError error={query.error} title={t('taskDetail.results.loadError')} onRetry={() => void query.refetch()} />
        ) : query.data.length === 0 ? (
          <p className={styles.muted}>{t('taskDetail.results.empty')}</p>
        ) : (
          <ul className={styles.stack}>
            {query.data.map((submission) => (
              <SubmissionView key={submission.id} submission={submission} />
            ))}
          </ul>
        )
      ) : null}
    </div>
  )
}

/** Kết quả đã nộp của vòng hiện tại (+ vòng cũ khi cần xem); minh chứng là liên kết mở ở tab mới. */
export function SubmissionsSection({ task }: { task: TaskDetail }) {
  const t = useT()
  const previous = task.rounds.filter((round) => round.id !== task.currentRound.id && round.roundNo < task.currentRound.roundNo)
  return (
    <Card as="section" id="results" aria-labelledby="results-title" className={styles.block}>
      <h2 id="results-title" className={styles.blockTitle}>
        {t('taskDetail.results.title')}
      </h2>
      {task.submissions.length === 0 ? (
        <p className={styles.muted}>{t('taskDetail.results.empty')}</p>
      ) : (
        <ul className={styles.stack}>
          {task.submissions.map((submission) => (
            <SubmissionView key={submission.id} submission={submission} />
          ))}
        </ul>
      )}
      {previous.length > 0 ? (
        <div className={styles.stack}>
          <h3 className={styles.subTitle}>{t('taskDetail.results.previousRounds')}</h3>
          {previous.map((round) => (
            <PreviousRound key={round.id} round={round} />
          ))}
        </div>
      ) : null}
    </Card>
  )
}
