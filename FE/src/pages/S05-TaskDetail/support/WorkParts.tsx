import { Link } from 'react-router'
import { TaskReviewDecision, type EvidenceItem, type SupportTimelineKind, type SupportWork } from '@/api/types'
import { paths } from '@/app/routes'
import {
  Card,
  MetaList,
  PointsText,
  StatusChip,
  type StatusTone,
  Timeline,
  TimelineEntry,
} from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { formatMinutes } from './formatMinutes'
import styles from './SupportWork.module.css'

const TIMELINE_TONES: Record<SupportTimelineKind, StatusTone> = {
  OFFER_ACCEPTED: 'info',
  RESULT_SUBMITTED: 'pending',
  REVISION_REQUESTED: 'warning',
  UNABLE_REPORTED: 'warning',
  DISCUSSION_RESOLVED: 'info',
  APPROVED: 'success',
}

export function EvidenceLinks({ items }: { items: EvidenceItem[] }) {
  return (
    <ul className={styles.evidenceList}>
      {items.map((item) => (
        <li key={item.url}>
          <a href={item.url} target="_blank" rel="noopener noreferrer">
            {item.label || item.url}
          </a>
        </li>
      ))}
    </ul>
  )
}

/** Phạm vi đã nhận, điểm P_t đã chốt (server tính) và nhãn `Chưa ghi điểm` cho đến khi TL duyệt. */
export function WorkInfo({ work }: { work: SupportWork }) {
  const t = useT()
  const f = useFormatters()
  return (
    <Card as="section" aria-labelledby="work-info" className={styles.block}>
      <h2 id="work-info" className={styles.blockTitle}>
        {t('supportWork.info.title')}
      </h2>
      <MetaList
        items={[
          { label: t('supportWork.info.task'), value: <Link to={paths.task(work.groupId, work.task.id)}>{work.task.title}</Link> },
          { label: t('supportWork.info.requester'), value: work.requester.name },
          { label: t('supportWork.info.helper'), value: work.helper.name },
          { label: t('supportWork.info.original'), value: work.originalAssignee.name },
          { label: t('supportWork.info.approver'), value: work.resultApprover.name },
          { label: t('fields.deadline'), value: <time dateTime={work.deadlineSnapshot}>{f.dateTime(work.deadlineSnapshot)}</time> },
          { label: t('fields.difficulty'), value: t(`status.difficulty.${work.difficultySnapshot}`) },
        ]}
      />
      {work.points ? (
        <div className={styles.row}>
          <PointsText>{f.pointsLabel(work.points)}</PointsText>
          <StatusChip
            tone={work.pointRecorded ? 'success' : 'neutral'}
            label={work.pointRecorded ? t('supportWork.info.recorded') : t('handoff.pendingNoPoints')}
            icon={work.pointRecorded ? 'check' : 'clock'}
            size="sm"
          />
        </div>
      ) : null}
      {work.pointLabel ? <p>{work.pointLabel}</p> : null}
      <p className={styles.muted}>{t('supportWork.info.pointRule')}</p>
      <h3 className={styles.subTitle}>{t('supportWork.info.scope')}</h3>
      <p className={styles.prewrap}>{work.scopeSnapshot || t('supportWork.info.notDescribed')}</p>
      <h3 className={styles.subTitle}>{t('supportWork.info.output')}</h3>
      <p className={styles.prewrap}>{work.expectedOutput || t('supportWork.info.notDescribed')}</p>
    </Card>
  )
}

/** Mọi phiên bản đã gửi (mới → cũ) và dòng thời gian sự kiện. Không xóa bản cũ. */
export function WorkHistory({ work }: { work: SupportWork }) {
  const t = useT()
  const f = useFormatters()
  const submissions = [...work.submissions].sort((a, b) => b.revisionNo - a.revisionNo)
  return (
    <>
      <Card as="section" aria-labelledby="work-history" className={styles.block}>
        <h2 id="work-history" className={styles.blockTitle}>
          {t('supportWork.history.title')}
        </h2>
        {submissions.length === 0 ? <p className={styles.muted}>{t('supportWork.history.empty')}</p> : null}
        {submissions.map((submission) => (
          <div key={submission.id} className={styles.submission}>
            <div className={styles.row}>
              <strong>{t('supportWork.history.version', { no: submission.revisionNo })}</strong>
              <time dateTime={submission.submittedAt}>{f.dateTime(submission.submittedAt)}</time>
              {submission.reviewDecision ? (
                <StatusChip
                  size="sm"
                  tone={submission.reviewDecision === TaskReviewDecision.ACCEPTED ? 'success' : 'warning'}
                  label={t(
                    submission.reviewDecision === TaskReviewDecision.ACCEPTED
                      ? 'supportWork.history.accepted'
                      : submission.reviewDecision === TaskReviewDecision.FIX_REQUIRED
                        ? 'supportWork.history.fix'
                        : 'supportWork.history.update',
                  )}
                />
              ) : null}
            </div>
            <p className={styles.prewrap}>{submission.resultNote}</p>
            <EvidenceLinks items={submission.evidence} />
            {submission.actualMinutes ? (
              <p className={styles.muted}>{t('supportWork.history.actual', { time: formatMinutes(t, submission.actualMinutes) })}</p>
            ) : null}
            {submission.issueDescription ? <p className={styles.prewrap}>{submission.issueDescription}</p> : null}
          </div>
        ))}
      </Card>
      <Card as="section" aria-labelledby="work-timeline" className={styles.block}>
        <h2 id="work-timeline" className={styles.blockTitle}>
          {t('supportWork.timeline.title')}
        </h2>
        <Timeline label={t('supportWork.timeline.title')}>
          {work.timeline.map((event) => (
            <TimelineEntry
              key={`${event.kind}-${event.at}`}
              title={t(`supportWork.timeline.${event.kind}` as MessageKey)}
              at={event.at}
              actorName={event.actor?.name}
              tone={TIMELINE_TONES[event.kind]}
            >
              {event.note ? <p className={styles.prewrap}>{event.note}</p> : null}
            </TimelineEntry>
          ))}
        </Timeline>
      </Card>
    </>
  )
}
