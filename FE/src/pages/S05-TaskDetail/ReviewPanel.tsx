import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useQueryClient } from '@tanstack/react-query'
import { useMembers } from '@/api/groups'
import { isApiError } from '@/api/errors'
import { taskKeys, useReviewPreview, useReviewResult, useSetIndependentReviewer } from '@/api/tasks'
import { ErrorCode, GroupRole, TaskReviewDecision, type TaskDetail, type TaskSubmission } from '@/api/types'
import { Button, Card, FormField, InlineError, LoadingSkeleton, Select, Textarea, useToast } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { useGroup } from '@/app/group/groupContext'
import styles from './TaskDetail.module.css'

interface DecisionValues {
  decision: TaskReviewDecision
  issueDescription: string
}

export function IndependentReviewer({ task }: { task: TaskDetail }) {
  const t = useT()
  const toast = useToast()
  const { groupId } = useGroup()
  const members = useMembers(groupId)
  const setReviewer = useSetIndependentReviewer(groupId, task.id)
  const [value, setValue] = useState(task.independentReviewer?.userId ?? '')
  const candidates = (members.data ?? []).filter((member) => member.role !== GroupRole.LEAD)

  return (
    <div className={styles.formStack}>
      <h3 className={styles.subTitle}>{t('taskDetail.review.independentTitle')}</h3>
      <p className={styles.muted}>{t('taskDetail.review.independentHint')}</p>
      <div className={styles.inlineForm}>
        <FormField label={t('taskDetail.review.independentPick')}>
          <Select value={value} onChange={(event) => setValue(event.target.value)}>
            <option value="">{t('taskDetail.review.independentNone')}</option>
            {candidates.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.name}
              </option>
            ))}
          </Select>
        </FormField>
        <Button
          variant="secondary"
          loading={setReviewer.isPending}
          onClick={() =>
            setReviewer.mutate(
              { reviewerUserId: value || null },
              { onSuccess: () => toast.success(t('taskDetail.review.independentSaved')) },
            )
          }
        >
          {t('taskDetail.review.independentSave')}
        </Button>
      </div>
      {setReviewer.isError ? <InlineError error={setReviewer.error} title={t('taskDetail.review.independentFailed')} /> : null}
    </div>
  )
}

/**
 * TL duyệt kết quả sau giờ checkpoint (spec S05): `Chấp nhận` / `Cần sửa` / `Cần cập nhật` (kèm mô tả bắt buộc).
 * Quyền duyệt, giờ chốt, điểm sẽ ghi do server tính qua `review-preview`; thao tác ghi gửi Idempotency-Key.
 */
export function ReviewPanel({ task, target }: { task: TaskDetail; target: TaskSubmission }) {
  const t = useT()
  const f = useFormatters()
  const toast = useToast()
  const queryClient = useQueryClient()
  const { groupId } = useGroup()
  const preview = useReviewPreview(target.id)
  const review = useReviewResult(groupId, task.id)
  const data = preview.data

  const form = useForm<DecisionValues>({
    resolver: zodResolver(
      z
        .object({
          decision: z.enum([TaskReviewDecision.ACCEPTED, TaskReviewDecision.FIX_REQUIRED, TaskReviewDecision.UPDATE_REQUIRED]),
          issueDescription: z.string().trim().max(2000, t('taskDetail.review.errIssue')),
        })
        .refine((v) => v.decision === TaskReviewDecision.ACCEPTED || v.issueDescription.trim().length >= 10, {
          path: ['issueDescription'],
          message: t('taskDetail.review.errIssue'),
        }),
    ),
    defaultValues: { decision: TaskReviewDecision.ACCEPTED, issueDescription: '' },
  })
  const decision = useWatch({ control: form.control, name: 'decision' })
  const stale = isApiError(review.error) && review.error.code === ErrorCode.STALE_SUBMISSION

  const onSubmit = form.handleSubmit((values) => {
    if (review.isPending) return
    review.mutate(
      {
        submissionId: target.id,
        decision: values.decision,
        issueDescription: values.decision === TaskReviewDecision.ACCEPTED ? null : values.issueDescription.trim(),
      },
      {
        onSuccess: (response) => {
          if (response.review.decision === TaskReviewDecision.ACCEPTED) {
            toast.success(
              response.award
                ? t('taskDetail.review.acceptedAward', { points: f.pointsLabel(response.award.points, { signed: true }), task: task.title })
                : t('taskDetail.review.acceptedPlain'),
            )
          } else {
            toast.success(
              response.review.decision === TaskReviewDecision.FIX_REQUIRED
                ? t('taskDetail.review.fixRecorded')
                : t('taskDetail.review.updateRecorded'),
            )
          }
        },
      },
    )
  })

  return (
    <Card as="section" aria-labelledby="review-title" className={styles.block}>
      <h2 id="review-title" className={styles.blockTitle}>
        {t('taskDetail.review.title')}
      </h2>
      <p>{t('taskDetail.review.target', { no: target.submissionNo, time: f.dateTime(target.submittedAt) })}</p>

      {preview.isPending ? <LoadingSkeleton variant="text" lines={3} /> : null}
      {preview.isError ? <InlineError error={preview.error} onRetry={() => void preview.refetch()} /> : null}

      {data ? (
        <>
          <p className={styles.muted}>{t('taskDetail.review.cutoff', { time: f.dateTime(data.cutoffAt) })}</p>
          {data.authorizedReviewer ? <p className={styles.muted}>{t('taskDetail.review.reviewer', { name: data.authorizedReviewer.name })}</p> : null}
          {data.alreadyReviewed ? <InlineError tone="warning" message={t('taskDetail.review.alreadyReviewed')} /> : null}
          {!data.canReview && !data.alreadyReviewed && data.cannotReviewReason ? (
            <InlineError tone="warning" title={t('taskDetail.review.cannot')} message={data.cannotReviewReason.message} />
          ) : null}
          {data.awardPreview ? (
            <div className={styles.awardBox}>
              <h3 className={styles.subTitle}>{t('taskDetail.review.awardTitle')}</h3>
              <p className={styles.awardLabel}>{data.awardPreview.scoreLabel}</p>
              <p>{data.awardPreview.effectLabel}</p>
              <p className={styles.muted}>{t('taskDetail.review.confirmedBefore', { points: f.pointsLabel(data.awardPreview.confirmedBefore) })}</p>
              <p className={styles.muted}>{t('taskDetail.review.confirmedAfter', { points: f.pointsLabel(data.awardPreview.confirmedAfter) })}</p>
              <p className={styles.muted}>{data.awardPreview.notice}</p>
            </div>
          ) : null}

          {data.canReview && !data.alreadyReviewed ? (
            <form noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
              <fieldset className={styles.fieldset}>
                <legend className={styles.subTitle}>{t('taskDetail.review.decision')}</legend>
                <div className={styles.radioRow}>
                  {[TaskReviewDecision.ACCEPTED, TaskReviewDecision.FIX_REQUIRED, TaskReviewDecision.UPDATE_REQUIRED].map((value) => (
                    <label key={value} className={styles.radio}>
                      <input type="radio" value={value} {...form.register('decision')} />
                      <span>
                        {value === TaskReviewDecision.ACCEPTED
                          ? t('taskDetail.review.accept')
                          : value === TaskReviewDecision.FIX_REQUIRED
                            ? t('taskDetail.review.fix')
                            : t('taskDetail.review.update')}
                      </span>
                    </label>
                  ))}
                </div>
              </fieldset>
              {decision !== TaskReviewDecision.ACCEPTED ? (
                <FormField
                  label={t('taskDetail.review.issue')}
                  required
                  hint={t('taskDetail.review.issueHint')}
                  error={form.formState.errors.issueDescription?.message}
                >
                  <Textarea rows={4} {...form.register('issueDescription')} />
                </FormField>
              ) : null}
              {review.isError ? (
                stale ? (
                  <InlineError
                    tone="warning"
                    message={t('taskDetail.review.stale')}
                    onRetry={() => {
                      review.reset()
                      void queryClient.invalidateQueries({ queryKey: taskKeys.detail(task.id) })
                      void queryClient.invalidateQueries({ queryKey: taskKeys.reviewPreview(target.id) })
                    }}
                    retryLabel={t('taskDetail.review.reload')}
                  />
                ) : (
                  <InlineError error={review.error} title={t('taskDetail.review.failed')} />
                )
              ) : null}
              <div className={styles.buttonRow}>
                <Button type="submit" loading={review.isPending}>
                  {review.isPending ? t('taskDetail.review.submitting') : t('taskDetail.review.submit')}
                </Button>
              </div>
            </form>
          ) : null}

        </>
      ) : null}

    </Card>
  )
}
