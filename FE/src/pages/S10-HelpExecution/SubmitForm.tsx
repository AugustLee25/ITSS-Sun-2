import { useForm, useFieldArray } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { isApiError } from '@/api/errors'
import { useRefreshSupportData, useSubmitSupportResult } from '@/api/supportWork'
import { EvidenceKind, type SupportWork } from '@/api/types'
import { Button, Card, FormField, Icon, InlineError, Input, Textarea, UnsavedChangesGuard } from '@/components'
import { useT } from '@/i18n'
import styles from './HelpWork.module.css'
import { useSingleFlight } from './singleFlight'

const MAX_EVIDENCE = 5

function isHttpsUrl(value: string): boolean {
  if (value.length > 2048) return false
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

interface Values {
  resultNote: string
  evidence: Array<{ url: string; label: string }>
  hours: string
  minutes: string
}

/** Form bàn giao (Helper): ≥ 1 link https, tóm tắt 10–1000 ký tự, thời gian thực tế tùy chọn (tự khai). */
export function SubmitForm({ work, onSubmitted }: { work: SupportWork; onSubmitted: () => void }) {
  const t = useT()
  const submit = useSubmitSupportResult(work.id, work.groupId)
  const refresh = useRefreshSupportData(work.groupId)
  const once = useSingleFlight()
  const schema = z
    .object({
      resultNote: z.string().trim().min(10, t('supportWork.submit.errNote')).max(1000, t('supportWork.submit.errNote')),
      evidence: z
        .array(
          z.object({
            url: z.string().trim().refine(isHttpsUrl, t('supportWork.submit.errUrl')),
            label: z.string().trim().max(200),
          }),
        )
        .min(1, t('supportWork.submit.errUrl'))
        .max(MAX_EVIDENCE, t('supportWork.submit.evidenceMax')),
      hours: z.string().trim().regex(/^\d{0,3}$/, t('supportWork.submit.errHours')),
      minutes: z
        .string()
        .trim()
        .regex(/^\d{0,2}$/, t('supportWork.submit.errMinutes'))
        .refine((v) => v === '' || Number(v) < 60, t('supportWork.submit.errMinutes')),
    })
    .refine((v) => (v.hours === '' && v.minutes === '') || Number(v.hours || 0) * 60 + Number(v.minutes || 0) > 0, {
      path: ['minutes'],
      message: t('supportWork.submit.errActual'),
    })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { resultNote: '', evidence: [{ url: '', label: '' }], hours: '', minutes: '' },
    mode: 'onTouched',
  })
  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'evidence' })
  const errors = form.formState.errors

  const onSubmit = form.handleSubmit((values) => {
    const total = Number(values.hours || 0) * 60 + Number(values.minutes || 0)
    const body = {
      resultNote: values.resultNote.trim(),
      evidence: values.evidence.map((item) => ({
        type: EvidenceKind.URL,
        url: item.url.trim(),
        ...(item.label.trim() ? { label: item.label.trim() } : {}),
      })),
      actualMinutes: total > 0 ? total : null,
    }
    once((release) =>
      submit.mutate(body, {
        onSuccess: onSubmitted,
        onError: (error) => {
          release()
          if (!isApiError(error)) return
          for (const fieldError of error.fieldErrors) {
            const name = fieldError.field === 'actualMinutes' ? 'minutes' : fieldError.field.replace(/\[(\d+)\]/g, '.$1')
            form.setError(name as never, { type: 'server', message: fieldError.message })
          }
          if (error.status === 409) refresh() // trạng thái phần việc đã đổi: tải lại, giữ nguyên dữ liệu đang nhập
        },
      }),
    )
  })

  return (
    <Card as="section" aria-labelledby="submit-title" className={styles.block}>
      <UnsavedChangesGuard when={form.formState.isDirty && !submit.isSuccess} />
      <h2 id="submit-title" className={styles.blockTitle}>
        {t('supportWork.submit.title')}
      </h2>
      <p className={styles.muted}>{t('supportWork.submit.description')}</p>
      <form noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
        <fieldset className={styles.fieldset}>
          <legend className={styles.subTitle}>{t('supportWork.submit.evidenceTitle')}</legend>
          {fields.map((field, index) => (
            <div key={field.id} className={styles.evidenceRow}>
              <FormField
                label={t('supportWork.submit.evidenceUrl', { no: index + 1 })}
                required
                hint={index === 0 ? t('supportWork.submit.evidenceUrlHint') : undefined}
                error={errors.evidence?.[index]?.url?.message}
              >
                <Input type="url" inputMode="url" autoComplete="off" placeholder="https://" {...form.register(`evidence.${index}.url`)} />
              </FormField>
              <FormField label={t('supportWork.submit.evidenceLabel', { no: index + 1 })} error={errors.evidence?.[index]?.label?.message}>
                <Input autoComplete="off" {...form.register(`evidence.${index}.label`)} />
              </FormField>
              {fields.length > 1 ? (
                <Button variant="ghost" onClick={() => remove(index)} aria-label={t('supportWork.submit.evidenceRemove', { no: index + 1 })}>
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
              {t('supportWork.submit.evidenceAdd')}
            </Button>
          ) : (
            <p className={styles.muted}>{t('supportWork.submit.evidenceMax')}</p>
          )}
        </fieldset>

        <FormField
          label={t('supportWork.submit.resultNote')}
          required
          hint={t('supportWork.submit.resultNoteHint')}
          error={errors.resultNote?.message}
        >
          <Textarea rows={4} {...form.register('resultNote')} />
        </FormField>

        <fieldset className={styles.fieldset}>
          <legend className={styles.subTitle}>{t('supportWork.submit.actual')}</legend>
          <p className={styles.muted}>{t('supportWork.submit.actualHint')}</p>
          <div className={styles.durationRow}>
            <FormField label={t('supportWork.submit.hours')} error={errors.hours?.message}>
              <Input type="number" inputMode="numeric" min={0} {...form.register('hours')} />
            </FormField>
            <FormField label={t('supportWork.submit.minutes')} error={errors.minutes?.message}>
              <Input type="number" inputMode="numeric" min={0} max={59} {...form.register('minutes')} />
            </FormField>
          </div>
        </fieldset>

        {submit.isError ? <InlineError error={submit.error} title={t('supportWork.submit.failed')} /> : null}
        <div className={styles.buttonRow}>
          <Button type="submit" loading={submit.isPending} icon="send">
            {submit.isPending ? t('supportWork.submit.sending') : t('supportWork.submit.button')}
          </Button>
        </div>
      </form>
    </Card>
  )
}
