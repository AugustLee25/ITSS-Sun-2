import { useForm, useFieldArray } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { isApiError } from '@/api/errors'
import { useSubmitResult, taskKeys } from '@/api/tasks'
import { ErrorCode, type SubmitResultResponse, type TaskDetail } from '@/api/types'
import { Button, FormField, Icon, InlineError, Input, Modal, Textarea } from '@/components'
import { useT } from '@/i18n'
import { buildEvidenceSchema, MAX_EVIDENCE, toEvidenceItems, type EvidenceValues } from './evidence'
import styles from './TaskDetail.module.css'

/** `Gửi kết quả`: nộp 1–5 đường dẫn minh chứng (spec S05/7.3: chưa có minh chứng thì không mở nút gửi). */
export function SubmitResultDialog({
  task,
  groupId,
  onClose,
  onSubmitted,
}: {
  task: TaskDetail
  groupId: string
  onClose: () => void
  onSubmitted: (response: SubmitResultResponse) => void
}) {
  const t = useT()
  const queryClient = useQueryClient()
  const submit = useSubmitResult(groupId, task.id)
  const form = useForm<EvidenceValues>({
    resolver: zodResolver(buildEvidenceSchema(t)),
    defaultValues: { resultNote: '', evidence: [{ url: '', label: '' }] },
    mode: 'onTouched',
  })
  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'evidence' })
  const errors = form.formState.errors
  const planId = task.effectivePlan?.id

  const onSubmit = form.handleSubmit((values) => {
    if (submit.isPending || !planId) return
    submit.mutate(
      {
        roundId: task.currentRound.id,
        planRevisionId: planId,
        resultNote: values.resultNote.trim() || null,
        evidence: toEvidenceItems(values),
      },
      {
        onSuccess: (response) => {
          onSubmitted(response)
          onClose()
        },
        onError: (error) => {
          if (!isApiError(error)) return
          for (const fieldError of error.fieldErrors) {
            form.setError(fieldError.field.replace(/\[(\d+)\]/g, '.$1') as never, { type: 'server', message: fieldError.message })
          }
          if (error.code === ErrorCode.PLAN_CHANGED) void queryClient.invalidateQueries({ queryKey: taskKeys.detail(task.id) })
        },
      },
    )
  })

  const dirty = form.formState.isDirty

  return (
    <Modal
      open
      size="md"
      title={t('taskDetail.exec.submitTitle')}
      description={t('taskDetail.exec.submitDescription')}
      onClose={() => !submit.isPending && onClose()}
      closeOnBackdrop={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submit.isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="submit-result-form" loading={submit.isPending} disabled={!planId}>
            {submit.isPending ? t('taskDetail.exec.submitting') : t('taskDetail.exec.submit')}
          </Button>
        </>
      }
    >
      <form id="submit-result-form" noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack} data-dirty={dirty}>
        <fieldset className={styles.fieldset}>
          <legend className={styles.blockTitle}>{t('taskDetail.exec.evidenceTitle')}</legend>
          {fields.map((field, index) => (
            <div key={field.id} className={styles.evidenceRow}>
              <FormField
                label={t('taskDetail.exec.evidenceUrl', { no: index + 1 })}
                required
                hint={index === 0 ? t('taskDetail.exec.evidenceUrlHint') : undefined}
                error={errors.evidence?.[index]?.url?.message}
              >
                <Input type="url" inputMode="url" autoComplete="off" placeholder="https://" {...form.register(`evidence.${index}.url`)} />
              </FormField>
              <FormField label={t('taskDetail.exec.evidenceLabel', { no: index + 1 })} error={errors.evidence?.[index]?.label?.message}>
                <Input autoComplete="off" {...form.register(`evidence.${index}.label`)} />
              </FormField>
              {fields.length > 1 ? (
                <Button variant="ghost" onClick={() => remove(index)} aria-label={t('taskDetail.exec.evidenceRemove', { no: index + 1 })}>
                  <Icon name="x" size={18} />
                </Button>
              ) : null}
            </div>
          ))}
          {errors.evidence?.message ? (
            <p role="alert" className={styles.fieldError}>
              {errors.evidence.message}
            </p>
          ) : null}
          {fields.length < MAX_EVIDENCE ? (
            <Button variant="secondary" icon="plus" onClick={() => append({ url: '', label: '' })}>
              {t('taskDetail.exec.evidenceAdd')}
            </Button>
          ) : (
            <p className={styles.muted}>{t('taskDetail.exec.evidenceMax')}</p>
          )}
        </fieldset>

        <FormField label={t('taskDetail.exec.resultNote')} error={errors.resultNote?.message}>
          <Textarea rows={3} {...form.register('resultNote')} />
        </FormField>
        <p className={styles.muted}>{t('taskDetail.exec.submitLateNotice')}</p>
        {submit.isError ? <InlineError error={submit.error} title={t('taskDetail.exec.submitFailed')} /> : null}
      </form>
    </Modal>
  )
}
