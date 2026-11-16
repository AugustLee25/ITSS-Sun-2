import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Link } from 'react-router'
import { isApiError } from '@/api/errors'
import { useRefreshSupportData, useReportUnable, useResolveDiscussion } from '@/api/supportWork'
import { SupportWorkResolution, type SupportWork } from '@/api/types'
import { paths } from '@/app/routes'
import { Button, Card, FormField, InlineError, Modal, Textarea, useToast } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import styles from './HelpWork.module.css'
import { useSingleFlight } from './singleFlight'

/** Helper: `Báo không thể hoàn thành` → `NEEDS_DISCUSSION`; không tự chuyển việc cho người khác, không trừ điểm. */
export function UnableDialog({ work, onClose }: { work: SupportWork; onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const refresh = useRefreshSupportData(work.groupId)
  const report = useReportUnable(work.id, work.groupId)
  const once = useSingleFlight()
  const form = useForm<{ reason: string }>({
    resolver: zodResolver(z.object({ reason: z.string().trim().min(5, t('supportWork.unable.errReason')).max(1000, t('supportWork.unable.errReason')) })),
    defaultValues: { reason: '' },
    mode: 'onTouched',
  })
  const onSubmit = form.handleSubmit(({ reason }) => {
    once((release) =>
      report.mutate(
        { reason: reason.trim(), version: work.version },
        {
          onSuccess: () => {
            toast.success(t('supportWork.unable.done'))
            onClose()
          },
          onError: (error) => {
            release()
            if (isApiError(error) && error.status === 409) refresh()
          },
        },
      ),
    )
  })
  return (
    <Modal
      open
      size="md"
      title={t('supportWork.unable.title')}
      description={t('supportWork.unable.description')}
      onClose={() => !report.isPending && onClose()}
      closeOnBackdrop={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={report.isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="unable-form" loading={report.isPending}>
            {t('supportWork.unable.submit')}
          </Button>
        </>
      }
    >
      <form id="unable-form" noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
        <FormField label={t('supportWork.unable.reason')} required hint={t('supportWork.unable.reasonHint')} error={form.formState.errors.reason?.message}>
          <Textarea rows={4} {...form.register('reason')} />
        </FormField>
        {report.isError ? <InlineError error={report.error} title={t('supportWork.unable.failed')} /> : null}
      </form>
    </Modal>
  )
}

interface ResolveValues {
  resolution: SupportWorkResolution
  note: string
}

/** TL: sau `NEEDS_DISCUSSION` chọn tiếp tục / đóng yêu cầu / mở lại yêu cầu; ghi ghi chú vào lịch sử. */
export function ResolveDiscussion({ work }: { work: SupportWork }) {
  const t = useT()
  const toast = useToast()
  const refresh = useRefreshSupportData(work.groupId)
  const resolve = useResolveDiscussion(work.id, work.groupId)
  const once = useSingleFlight()
  const form = useForm<ResolveValues>({
    resolver: zodResolver(
      z.object({
        resolution: z.enum([SupportWorkResolution.RESUME, SupportWorkResolution.CANCEL_REQUEST, SupportWorkResolution.REOPEN_REQUEST]),
        note: z.string().trim().min(5, t('supportWork.resolve.errNote')).max(1000, t('supportWork.resolve.errNote')),
      }),
    ),
    defaultValues: { resolution: SupportWorkResolution.RESUME, note: '' },
  })
  const onSubmit = form.handleSubmit((values) => {
    once((release) =>
      resolve.mutate(
        { resolution: values.resolution, note: values.note.trim(), version: work.version },
        {
          onSuccess: () => toast.success(t('supportWork.resolve.done')),
          onError: (error) => {
            release()
            if (isApiError(error) && error.status === 409) refresh()
          },
        },
      ),
    )
  })

  if (work.successorHelpRequestId) {
    return (
      <Card as="section" className={styles.block}>
        <p>{t('supportWork.resolve.successor')}</p>
        <Link to={paths.helpRequest(work.groupId, work.successorHelpRequestId)}>{t('supportWork.resolve.openSuccessor')}</Link>
      </Card>
    )
  }
  return (
    <Card as="section" aria-labelledby="resolve-title" className={styles.block}>
      <h2 id="resolve-title" className={styles.blockTitle}>
        {t('supportWork.resolve.title')}
      </h2>
      <form noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
        <fieldset className={styles.fieldset}>
          <legend className={styles.subTitle}>{t('supportWork.resolve.resolution')}</legend>
          <div className={styles.radioRow}>
            {Object.values(SupportWorkResolution).map((value) => (
              <label key={value} className={styles.radio}>
                <input type="radio" value={value} {...form.register('resolution')} />
                <span>{t(`supportWork.resolve.${value}` as MessageKey)}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <FormField label={t('supportWork.resolve.note')} required hint={t('supportWork.resolve.noteHint')} error={form.formState.errors.note?.message}>
          <Textarea rows={3} {...form.register('note')} />
        </FormField>
        {resolve.isError ? <InlineError error={resolve.error} title={t('supportWork.resolve.failed')} /> : null}
        <div className={styles.buttonRow}>
          <Button type="submit" loading={resolve.isPending}>
            {t('supportWork.resolve.submit')}
          </Button>
        </div>
      </form>
    </Card>
  )
}
