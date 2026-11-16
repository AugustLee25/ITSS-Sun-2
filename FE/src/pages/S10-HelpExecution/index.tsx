import { useState } from 'react'
import { SupportWorkAction, SupportWorkStatus, type SupportWork } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import { Button, ButtonLink, Card, InlineError, Icon, PageHeader, StatusChip, useStatusChip, type IconName } from '@/components'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { ResolveDiscussion, UnableDialog } from './Discussion'
import { SubmitForm } from './SubmitForm'
import { WorkGate, WorkHistory, WorkInfo } from './WorkParts'
import styles from './HelpWork.module.css'

/** Biểu ngữ trạng thái: `Đã gửi kết quả — chờ xác nhận` KHÁC `Đóng góp đã được công nhận` (chữ + biểu tượng + màu). */
function StateBanner({ work, justSubmitted, canResolve }: { work: SupportWork; justSubmitted: boolean; canResolve: boolean }) {
  const t = useT()
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
        {justSubmitted && work.status === SupportWorkStatus.SUBMITTED ? <p className={styles.bannerTitle}>{t('supportWork.thanks.title')}</p> : null}
        <p className={justSubmitted && work.status === SupportWorkStatus.SUBMITTED ? undefined : styles.bannerTitle}>{title}</p>
        {body ? <p>{body}</p> : null}
        {confirmedBy ? <p>{t('supportWork.state.confirmedBy', { name: confirmedBy })}</p> : null}
        {work.status === SupportWorkStatus.REVISION_REQUESTED && lastIssue ? <p className={styles.prewrap}>{lastIssue}</p> : null}
      </div>
    </div>
  )
}

function WorkView({ work }: { work: SupportWork }) {
  const t = useT()
  const chip = useStatusChip()
  const { groupId, isLeader } = useGroup()
  const [justSubmitted, setJustSubmitted] = useState(false)
  const [unableOpen, setUnableOpen] = useState(false)
  const allowed = new Set(work.allowedActions)
  const canResolve = allowed.has(SupportWorkAction.RESOLVE_DISCUSSION)
  const ledgerEntryId = work.contribution?.ledgerEntryId
  const pointsHref = `${paths.points(groupId)}?tab=history${ledgerEntryId ? `&ledgerEntryId=${encodeURIComponent(ledgerEntryId)}` : ''}`

  return (
    <>
      <PageHeader
        title={work.title}
        breadcrumbs={[{ label: t('nav.help'), to: paths.help(groupId) }, { label: work.title }]}
        backTo={paths.helpRequest(groupId, work.helpRequestId)}
        status={<StatusChip {...chip('work', work.status)} />}
      />
      <div className={styles.page}>
        <StateBanner work={work} justSubmitted={justSubmitted} canResolve={canResolve} />
        <WorkInfo work={work} />

        {allowed.has(SupportWorkAction.SUBMIT_RESULT) ? <SubmitForm work={work} onSubmitted={() => setJustSubmitted(true)} /> : null}
        {!work.canSubmit && work.cannotSubmitReason && work.status !== SupportWorkStatus.APPROVED ? (
          <InlineError tone="warning" message={work.cannotSubmitReason.message} />
        ) : null}
        {work.roundId === null && work.status !== SupportWorkStatus.APPROVED ? (
          <Card as="section" className={styles.block}>
            <p>{t('supportWork.info.waitingRound')}</p>
            {isLeader ? (
              <div className={styles.buttonRow}>
                <ButtonLink variant="secondary" to={paths.task(groupId, work.task.id)}>
                  {t('supportWork.info.setDeadline')}
                </ButtonLink>
              </div>
            ) : null}
          </Card>
        ) : null}

        {allowed.has(SupportWorkAction.REPORT_UNABLE) || allowed.has(SupportWorkAction.REVIEW_RESULT) || allowed.has(SupportWorkAction.VIEW_CONTRIBUTION) ? (
          <div className={styles.buttonRow}>
            {allowed.has(SupportWorkAction.REVIEW_RESULT) ? (
              <ButtonLink to={paths.helpReview(groupId, work.helpRequestId)} icon="check">
                {t('supportWork.action.review')}
              </ButtonLink>
            ) : null}
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
      </div>
      {unableOpen ? <UnableDialog work={work} onClose={() => setUnableOpen(false)} /> : null}
    </>
  )
}

/** S10 — Thực hiện & Bàn giao hỗ trợ: Helper bắt đầu/nộp/báo không thể; Requester/TL theo dõi. Mọi quyền do `allowedActions`. */
export default function S10HelpExecution() {
  return <WorkGate screen="S10">{(work) => <WorkView work={work} />}</WorkGate>
}
