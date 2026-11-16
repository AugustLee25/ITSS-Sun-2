import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { applyFieldErrors } from '@/api/errors'
import { useProgressUpdate } from '@/api/tasks'
import { RiskStatus, type TaskDetail } from '@/api/types'
import { Button, FormField, InlineError, Input, Modal, Select, Textarea, useToast } from '@/components'
import { useT } from '@/i18n'
import { zonedTimeToIso } from '@/lib/zonedTime'
import styles from './TaskDetail.module.css'

interface ProgressValues {
  note: string
  riskStatus: string
  expectedDate: string
  expectedTime: string
}

/** `Cập nhật tiến độ`: ghi một dòng tiến độ, không đổi deadline/cam kết (BR04). */
export function ProgressDialog({ task, groupId, timeZone, onClose }: { task: TaskDetail; groupId: string; timeZone: string; onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const progress = useProgressUpdate(groupId, task.id)
  const form = useForm<ProgressValues>({
    resolver: zodResolver(
      z.object({
        note: z.string().trim().min(1, t('taskDetail.exec.errProgressNote')).max(1000, t('taskDetail.exec.errProgressNote')),
        riskStatus: z.string(),
        expectedDate: z.string(),
        expectedTime: z.string(),
      }),
    ),
    defaultValues: { note: '', riskStatus: '', expectedDate: '', expectedTime: '20:00' },
  })
  const errors = form.formState.errors

  const onSubmit = form.handleSubmit((values) => {
    if (progress.isPending) return
    const expected = values.expectedDate ? zonedTimeToIso(values.expectedDate, values.expectedTime || '20:00', timeZone) : null
    progress.mutate(
      {
        note: values.note.trim(),
        ...(values.riskStatus ? { riskStatus: values.riskStatus as RiskStatus } : {}),
        ...(expected ? { expectedCompletionAt: expected } : {}),
      },
      {
        onSuccess: () => {
          toast.success(t('taskDetail.exec.progressSaved'))
          onClose()
        },
        onError: (error) => {
          applyFieldErrors(error, form.setError)
        },
      },
    )
  })

  return (
    <Modal
      open
      size="md"
      title={t('taskDetail.exec.progressTitle')}
      description={t('taskDetail.exec.progressDescription')}
      onClose={() => !progress.isPending && onClose()}
      closeOnBackdrop={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={progress.isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="progress-form" loading={progress.isPending}>
            {progress.isPending ? t('taskDetail.exec.progressSaving') : t('taskDetail.exec.progressSave')}
          </Button>
        </>
      }
    >
      <form id="progress-form" noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
        <FormField label={t('taskDetail.exec.progressNote')} required error={errors.note?.message}>
          <Textarea rows={3} {...form.register('note')} />
        </FormField>
        <FormField label={t('taskDetail.exec.progressRisk')}>
          <Select {...form.register('riskStatus')}>
            <option value="">{t('taskDetail.exec.progressRiskKeep')}</option>
            {[RiskStatus.NORMAL, RiskStatus.AT_RISK, RiskStatus.BLOCKED].map((status) => (
              <option key={status} value={status}>
                {t(`status.risk.${status}`)}
              </option>
            ))}
          </Select>
        </FormField>
        <div className={styles.row2}>
          <FormField label={t('taskDetail.exec.progressExpected')} hint={t('taskDetail.exec.progressExpectedHint')}>
            <Input type="date" {...form.register('expectedDate')} />
          </FormField>
          <FormField label={t('taskDetail.exec.progressExpectedTime')}>
            <Input type="time" {...form.register('expectedTime')} />
          </FormField>
        </div>
        {progress.isError ? <InlineError error={progress.error} title={t('taskDetail.exec.progressFailed')} /> : null}
      </form>
    </Modal>
  )
}
