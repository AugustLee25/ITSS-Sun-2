import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate } from 'react-router'
import { useQueryClient } from '@tanstack/react-query'
import { isApiError } from '@/api/errors'
import { useRefreshSupportData } from '@/api/supportWork'
import { taskKeys, useReviewPreview, useReviewResult } from '@/api/tasks'
import { SupportWorkAction, TaskReviewDecision, type ReviewPreviewResponse, type ReviewResultResponse, type SupportWork } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import {
  Button,
  ButtonLink,
  Card,
  EmptyState,
  FormField,
  InlineError,
  LoadingSkeleton,
  PageHeader,
  StatusChip,
  Textarea,
  useStatusChip,
  useToast,
} from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { ResolveDiscussion } from '../S10-HelpExecution/Discussion'
import { formatMinutes } from '../S10-HelpExecution/formatMinutes'
import { useSingleFlight } from '../S10-HelpExecution/singleFlight'
import { EvidenceLinks, WorkGate, WorkHistory, WorkInfo } from '../S10-HelpExecution/WorkParts'
import styles from '../S10-HelpExecution/HelpWork.module.css'

const DECISIONS = [TaskReviewDecision.ACCEPTED, TaskReviewDecision.FIX_REQUIRED, TaskReviewDecision.UPDATE_REQUIRED] as const

function ResultCard({ work, data }: { work: SupportWork; data: ReviewPreviewResponse }) {
  const t = useT()
  const f = useFormatters()
  const { submission } = data
  const own = work.submissions.find((s) => s.taskSubmissionId === submission.id)
  return (
    <Card as="section" aria-labelledby="result-title" className={styles.block}>
      <h2 id="result-title" className={styles.blockTitle}>
        {t('supportReview.result.title')}
      </h2>
      <div className={styles.row}>
        <strong>{t('supportReview.result.version', { no: submission.submissionNo, time: f.dateTime(submission.submittedAt) })}</strong>
        <StatusChip
          size="sm"
          tone={submission.isOnTime ? 'success' : 'warning'}
          label={submission.isOnTime ? t('supportReview.result.onTime') : t('supportReview.result.late')}
        />
        {submission.isReviewTarget ? <StatusChip size="sm" tone="info" label={t('supportReview.result.target')} /> : null}
      </div>
      <p className={styles.muted}>
        {t('supportReview.scope.round')}: {data.cycle.label} · {t('supportReview.scope.cutoff', { time: f.dateTime(data.cutoffAt) })}
      </p>
      {submission.resultNote ? (
        <>
          <h3 className={styles.subTitle}>{t('supportReview.result.note')}</h3>
          <p className={styles.prewrap}>{submission.resultNote}</p>
        </>
      ) : null}
      <h3 className={styles.subTitle}>{t('supportReview.result.evidence')}</h3>
      <EvidenceLinks items={submission.evidence} />
      {own?.actualMinutes ? (
        <p className={styles.muted}>
          {t('supportReview.result.actual', { time: formatMinutes(t, own.actualMinutes) })} · {t('supportReview.result.actualHint')}
        </p>
      ) : null}
    </Card>
  )
}

function AwardCard({ data }: { data: ReviewPreviewResponse }) {
  const t = useT()
  const f = useFormatters()
  const award = data.awardPreview
  return (
    <Card as="section" aria-labelledby="award-title" className={styles.block}>
      <h2 id="award-title" className={styles.blockTitle}>
        {t('supportReview.award.title')}
      </h2>
      {award ? (
        <div className={styles.awardBox}>
          <p className={styles.awardLabel}>{award.scoreLabel}</p>
          <p>{award.effectLabel}</p>
          <p>{t('supportReview.award.before', { points: f.pointsLabel(award.confirmedBefore) })}</p>
          <p>{t('supportReview.award.after', { points: f.pointsLabel(award.confirmedAfter) })}</p>
          <p>{award.notice}</p>
          <p className={styles.muted}>{t('supportReview.award.fixed')}</p>
        </div>
      ) : (
        <p>{t('supportReview.award.none')}</p>
      )}
    </Card>
  )
}

function DecisionForm({ work, data }: { work: SupportWork; data: ReviewPreviewResponse }) {
  const t = useT()
  const f = useFormatters()
  const toast = useToast()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { groupId } = useGroup()
  const refreshSupport = useRefreshSupportData(groupId)
  const review = useReviewResult(groupId, work.task.id)
  const once = useSingleFlight()
  const form = useForm<{ decision: TaskReviewDecision; issueDescription: string }>({
    resolver: zodResolver(
      z
        .object({ decision: z.enum(DECISIONS), issueDescription: z.string().trim().max(2000, t('supportReview.decision.errIssue')) })
        .refine((v) => v.decision === TaskReviewDecision.ACCEPTED || v.issueDescription.length >= 10, {
          path: ['issueDescription'],
          message: t('supportReview.decision.errIssue'),
        }),
    ),
    defaultValues: { decision: TaskReviewDecision.ACCEPTED, issueDescription: '' },
  })
  const decision = useWatch({ control: form.control, name: 'decision' })
  const conflict = isApiError(review.error) && review.error.status === 409
  const denied = isApiError(review.error) && review.error.status === 403

  const onSubmit = form.handleSubmit((values) => {
    const body = {
      submissionId: data.submission.id,
      decision: values.decision,
      issueDescription: values.decision === TaskReviewDecision.ACCEPTED ? null : values.issueDescription.trim(),
    }
    const onSuccess = (response: ReviewResultResponse) => {
      refreshSupport()
      const back = paths.helpWork(groupId, work.helpRequestId)
      if (response.review.decision !== TaskReviewDecision.ACCEPTED) {
        toast.success(t(response.review.decision === TaskReviewDecision.FIX_REQUIRED ? 'supportReview.done.fix' : 'supportReview.done.update'))
        void navigate(back)
        return
      }
      const award = response.award
      toast.success(
        award
          ? t('supportReview.done.acceptedAward', { points: f.pointsLabel(award.points, { signed: true }), name: award.recipient.name })
          : t('supportReview.done.acceptedPlain'),
      )
      void navigate(award ? `${paths.points(groupId)}?tab=history&ledgerEntryId=${encodeURIComponent(award.ledgerEntryId)}` : back)
    }
    once((release) => review.mutate(body, { onSuccess, onError: release }))
  })

  return (
    <Card as="section" aria-labelledby="decision-title" className={styles.block}>
      <h2 id="decision-title" className={styles.blockTitle}>
        {t('supportReview.decision.title')}
      </h2>
      <form noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
        <fieldset className={styles.fieldset}>
          <legend className="visually-hidden">{t('supportReview.decision.title')}</legend>
          <div className={styles.radioRow}>
            {DECISIONS.map((value) => (
              <label key={value} className={styles.radio}>
                <input type="radio" value={value} {...form.register('decision')} />
                <span>
                  {value === TaskReviewDecision.ACCEPTED
                    ? t('supportReview.decision.accept')
                    : value === TaskReviewDecision.FIX_REQUIRED
                      ? t('supportReview.decision.fix')
                      : t('supportReview.decision.update')}
                </span>
              </label>
            ))}
          </div>
        </fieldset>
        {decision !== TaskReviewDecision.ACCEPTED ? (
          <FormField label={t('supportReview.decision.issue')} required hint={t('supportReview.decision.issueHint')} error={form.formState.errors.issueDescription?.message}>
            <Textarea rows={4} {...form.register('issueDescription')} />
          </FormField>
        ) : null}
        {review.isError ? (
          <>
            <InlineError
              tone={conflict ? 'warning' : 'error'}
              title={t('supportReview.decision.failed')}
              error={review.error}
              message={isApiError(review.error) && review.error.code === 'STALE_SUBMISSION' ? t('supportReview.stale') : undefined}
              retryLabel={t('supportReview.reload')}
              onRetry={
                conflict
                  ? () => {
                      review.reset()
                      refreshSupport()
                      void queryClient.invalidateQueries({ queryKey: taskKeys.reviewPreview(data.submission.id) })
                    }
                  : undefined
              }
            />
            {denied ? <p className={styles.muted}>{t('supportReview.permission.rule')}</p> : null}
          </>
        ) : null}
        <div className={styles.stickyBar}>
          <Button type="submit" fullWidth loading={review.isPending}>
            {review.isPending
              ? t('supportReview.decision.sending')
              : decision === TaskReviewDecision.ACCEPTED
                ? t('supportReview.decision.submitAccept')
                : decision === TaskReviewDecision.FIX_REQUIRED
                  ? t('supportReview.decision.submitFix')
                  : t('supportReview.decision.submitUpdate')}
          </Button>
        </div>
      </form>
    </Card>
  )
}

function ReviewView({ work }: { work: SupportWork }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const { groupId } = useGroup()
  const preview = useReviewPreview(work.latestTaskSubmissionId)
  const data = preview.data
  const workHref = paths.helpWork(groupId, work.helpRequestId)
  const canResolve = work.allowedActions.includes(SupportWorkAction.RESOLVE_DISCUSSION)

  return (
    <>
      <PageHeader
        title={t('screens.S11')}
        description={work.title}
        breadcrumbs={[{ label: t('nav.help'), to: paths.help(groupId) }, { label: work.title, to: workHref }, { label: t('screens.S11') }]}
        backTo={workHref}
        status={<StatusChip {...chip('work', work.status)} />}
      />
      <div className={styles.page}>
        {!work.latestTaskSubmissionId ? (
          <EmptyState
            icon="inbox"
            title={t('supportReview.noSubmission.title')}
            description={t('supportReview.noSubmission.description')}
            action={<ButtonLink to={workHref}>{t('supportReview.noSubmission.cta')}</ButtonLink>}
          />
        ) : preview.isPending ? (
          <LoadingSkeleton variant="list" count={3} label={t('states.loading')} />
        ) : preview.isError || !data ? (
          <InlineError error={preview.error} title={t('supportWork.loadError')} onRetry={() => void preview.refetch()} />
        ) : (
          <div className={styles.cols}>
            <div className={styles.col}>
              <ResultCard work={work} data={data} />
              <AwardCard data={data} />
              {data.alreadyReviewed ? (
                <InlineError
                  tone="warning"
                  message={t(
                    data.submission.review ? `supportReview.reviewed.${data.submission.review.decision}` : 'supportReview.reviewed.generic',
                  )}
                />
              ) : data.canReview ? (
                <DecisionForm work={work} data={data} />
              ) : (
                <Card as="section" accent="warning" aria-labelledby="perm-title" className={styles.block}>
                  <h2 id="perm-title" className={styles.blockTitle}>
                    {t('supportReview.permission.title')}
                  </h2>
                  {data.cannotReviewReason ? <p>{data.cannotReviewReason.message}</p> : null}
                  {data.authorizedReviewer ? <p>{t('supportReview.permission.reviewer', { name: data.authorizedReviewer.name })}</p> : null}
                  <p className={styles.muted}>{t('supportReview.permission.rule')}</p>
                  <p className={styles.muted}>{t('supportReview.scope.cutoff', { time: f.dateTime(data.cutoffAt) })}</p>
                </Card>
              )}
            </div>
            <div className={styles.col}>
              <WorkInfo work={work} />
              {canResolve ? <ResolveDiscussion work={work} /> : null}
              <WorkHistory work={work} />
            </div>
          </div>
        )}
      </div>
    </>
  )
}

/** S11 — Xác nhận kết quả & ghi điểm hỗ trợ: duyệt đúng bản nộp, xem trước điểm do server tính, quyết định có `Idempotency-Key`. */
export default function S11Review() {
  return <WorkGate screen="S11">{(work) => <ReviewView work={work} />}</WorkGate>
}
