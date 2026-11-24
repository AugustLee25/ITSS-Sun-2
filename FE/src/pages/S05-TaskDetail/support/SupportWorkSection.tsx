import { useState } from 'react'
import { Link } from 'react-router'
import { hasStatus } from '@/api/errors'
import { useSupportWork } from '@/api/supportWork'
import { HelpRequestStatus, SupportWorkAction, SupportWorkStatus, type SupportWork, type TaskDetail } from '@/api/types'
import { paths } from '@/app/routes'
import { Button, ButtonLink, Icon, InlineError, LoadingSkeleton, StatusChip, useStatusChip, type IconName } from '@/components'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { ResolveDiscussion, UnableDialog } from './Discussion'
import { WorkHistory, WorkInfo } from './WorkParts'
import styles from './SupportWork.module.css'

/** Biểu ngữ trạng thái: `Chờ nghiệm thu — chưa tính điểm` KHÁC `Đóng góp đã được công nhận` (chữ + biểu tượng + màu). */
/** Yêu cầu hỗ trợ đã bị TL đóng (hoàn trả cho người gốc hoặc đăng lại): phần việc này không còn tiếp diễn (T-04). */
function isReturned(work: SupportWork): boolean {
  return work.helpRequestStatus === HelpRequestStatus.CANCELLED && work.status !== SupportWorkStatus.APPROVED
}

function StateBanner({ work, canResolve }: { work: SupportWork; canResolve: boolean }) {
  const t = useT()
  if (isReturned(work)) {
    return (
      <div role="status" className={cx(styles.banner, styles.toneInfo)} data-testid="handoff-returned">
        <Icon name="check" size={24} />
        <div>
          <p className={styles.bannerTitle}>{t('handoff.returnedTitle')}</p>
          <p>{work.successorHelpRequestId ? t('handoff.returnedReopened') : t('handoff.returnedBody')}</p>
          {work.successorHelpRequestId ? (
            <p>
              <Link to={paths.helpRequest(work.groupId, work.successorHelpRequestId)}>{t('supportWork.resolve.openSuccessor')}</Link>
            </p>
          ) : null}
        </div>
      </div>
    )
  }
  const lastIssue = [...work.submissions].reverse().find((s) => s.issueDescription)?.issueDescription
  let tone = styles.toneInfo
  let icon: IconName = 'clock'
  let title = t('supportWork.state.progressTitle')
  let body: string | null = t('supportWork.state.progressBody')
  switch (work.status) {
    case SupportWorkStatus.SUBMITTED:
      tone = styles.tonePending
      icon = 'hourglass'
      title = t('supportWork.state.pendingTitle')
      body = t('supportWork.state.pendingBody')
      break
    case SupportWorkStatus.APPROVED:
      tone = styles.toneSuccess
      icon = 'check'
      title = t('supportWork.state.confirmedTitle')
      body = t('supportWork.state.confirmedBody')
      break
    case SupportWorkStatus.REVISION_REQUESTED:
      tone = styles.toneWarning
      icon = 'alert'
      title = t('supportWork.state.revisionTitle')
      body = t('supportWork.state.revisionBody')
      break
    case SupportWorkStatus.NEEDS_DISCUSSION:
      tone = styles.toneWarning
      icon = 'alert'
      title = t('supportWork.state.discussionTitle')
      body = canResolve ? t('supportWork.state.discussionLead') : t('supportWork.state.discussionHelper')
      break
  }
  const confirmedBy = work.status === SupportWorkStatus.APPROVED ? work.contribution?.confirmedBy?.name : undefined
  return (
    <div role="status" className={cx(styles.banner, tone)}>
      <Icon name={icon} size={24} />
      <div>
        <p className={styles.bannerTitle}>{title}</p>
        {body ? <p>{body}</p> : null}
        {work.status !== SupportWorkStatus.APPROVED ? <p data-testid="handoff-pending-points">{t('handoff.pendingNoPoints')}</p> : null}
        {confirmedBy ? <p>{t('supportWork.state.confirmedBy', { name: confirmedBy })}</p> : null}
        {work.status === SupportWorkStatus.REVISION_REQUESTED && lastIssue ? <p className={styles.prewrap}>{lastIssue}</p> : null}
      </div>
    </div>
  )
}

function summaryKey(work: SupportWork) {
  if (work.status === SupportWorkStatus.APPROVED) return 'handoff.summaryDone' as const
  if (isReturned(work)) return 'handoff.summaryReturned' as const
  return 'handoff.summary' as const
}

/**
 * Solution 2 — task nhận làm thay hiển thị NGAY trong chi tiết task (S05): không có màn "My Support Work".
 * Nộp kết quả dùng đúng popup `Gửi kết quả` của task; duyệt dùng đúng khung duyệt của task. Ở đây chỉ có trạng thái,
 * phạm vi đã nhận, lịch sử bàn giao và hai thao tác riêng của hỗ trợ: Helper `Báo không thể hoàn thành`, TL thống nhất xử lý.
 * Người không liên quan (403/404) không thấy khối này.
 */
export function SupportWorkSection({ task }: { task: TaskDetail }) {
  const t = useT()
  const chip = useStatusChip()
  const workId = task.supportWorkId ?? task.lastSupportWorkId ?? null
  const query = useSupportWork(workId)
  const [unableOpen, setUnableOpen] = useState(false)

  if (!workId) return null
  if (query.isPending) return <LoadingSkeleton variant="list" count={1} label={t('states.loading')} />
  if (query.isError) {
    if (hasStatus(query.error, 403, 404)) return null
    return <InlineError error={query.error} title={t('supportWork.loadError')} onRetry={() => void query.refetch()} />
  }
  const work = query.data
  const allowed = new Set(work.allowedActions)
  const canResolve = allowed.has(SupportWorkAction.RESOLVE_DISCUSSION)
  const ledgerEntryId = work.contribution?.ledgerEntryId
  const pointsHref = `${paths.points(work.groupId)}?tab=history${ledgerEntryId ? `&ledgerEntryId=${encodeURIComponent(ledgerEntryId)}` : ''}`

  return (
    <section aria-labelledby="handoff-title" className={styles.page} data-testid="handoff-section">
      <div className={styles.row}>
        <h2 id="handoff-title" className={styles.blockTitle}>
          {t('handoff.sectionTitle')}
        </h2>
        {isReturned(work) ? <StatusChip tone="neutral" label={t('handoff.returnedChip')} /> : <StatusChip {...chip('work', work.status)} />}
      </div>
      <p>{t(summaryKey(work), { helper: work.helper.name, original: work.originalAssignee.name })}</p>
      <StateBanner work={work} canResolve={canResolve} />
      <WorkInfo work={work} />
      {work.roundId === null && work.status !== SupportWorkStatus.APPROVED ? (
        <p className={styles.muted}>{t('supportWork.info.waitingRound')}</p>
      ) : null}
      {allowed.has(SupportWorkAction.REPORT_UNABLE) || allowed.has(SupportWorkAction.VIEW_CONTRIBUTION) ? (
        <div className={styles.buttonRow}>
          {allowed.has(SupportWorkAction.VIEW_CONTRIBUTION) ? (
            <ButtonLink variant="secondary" to={pointsHref} icon="coins">
              {t('supportWork.action.contribution')}
            </ButtonLink>
          ) : null}
          {allowed.has(SupportWorkAction.REPORT_UNABLE) ? (
            <Button variant="secondary" onClick={() => setUnableOpen(true)}>
              {t('supportWork.unable.button')}
            </Button>
          ) : null}
        </div>
      ) : null}
      {canResolve ? <ResolveDiscussion work={work} /> : null}
      <WorkHistory work={work} />
      {unableOpen ? <UnableDialog work={work} onClose={() => setUnableOpen(false)} /> : null}
    </section>
  )
}
