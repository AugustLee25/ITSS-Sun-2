import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useCreateGroup } from '@/api/groups'
import { useSchedulePreview } from '@/api/schedule'
import { isApiError } from '@/api/errors'
import { ErrorCode, type CreateGroupRequest, type CreateGroupResponse } from '@/api/types'
import { paths } from '@/app/routes'
import { Button, FormField, InlineError, Input, Modal, UnsavedChangesGuard } from '@/components'
import { useT, type TFunction } from '@/i18n'
import { cx } from '@/lib/cx'
import { InviteShare } from './InviteShare'
import { ScheduleFields, SchedulePreviewPanel } from './ScheduleFields'
import { DEFAULT_SCHEDULE, toPreviewRequest, toScheduleInput, todayInputValue, type ScheduleValues } from './scheduleForm'
import styles from './GroupSetup.module.css'

interface CreateValues extends ScheduleValues {
  name: string
  courseName: string
  startDate: string
  deadline: string
}

type FieldName = keyof CreateValues

const STEP1_FIELDS: FieldName[] = ['name', 'courseName', 'startDate', 'deadline']
const SCHEDULE_FIELD_MAP: Record<string, FieldName> = {
  'schedule.weekday': 'weekday',
  'schedule.cutoffTime': 'cutoffTime',
  'schedule.timezone': 'timezone',
  'schedule.reportingWindowMinutes': 'window',
  scheduleAgreed: 'agreed',
}

function buildSchema(t: TFunction) {
  return z.object({
    name: z.string().trim().min(3, t('groupSetup.errNameLength')).max(80, t('groupSetup.errNameLength')),
    courseName: z.string().trim().max(120, t('groupSetup.errCourseLength')),
    startDate: z.string().min(1, t('groupSetup.errStartRequired')),
    deadline: z.string().min(1, t('groupSetup.errDeadlineRequired')),
    weekday: z.string().min(1),
    cutoffTime: z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/, t('groupSetup.errCutoffRequired')),
    timezone: z.string().min(1),
    window: z.string(),
    agreed: z.boolean(),
  })
}

/** S02 (tạo): wizard 2 bước — thông tin chung → lịch checkpoint do nhóm thống nhất (spec S02). */
export function CreateGroup() {
  const t = useT()
  const navigate = useNavigate()
  const create = useCreateGroup()
  const [step, setStep] = useState<1 | 2>(1)
  const [created, setCreated] = useState<CreateGroupResponse | null>(null)

  const form = useForm<CreateValues>({
    resolver: zodResolver(buildSchema(t)),
    defaultValues: { name: '', courseName: '', startDate: todayInputValue(), deadline: '', ...DEFAULT_SCHEDULE },
    mode: 'onTouched',
  })
  const watched = useWatch({ control: form.control }) as Partial<CreateValues>
  const values: CreateValues = { name: '', courseName: '', startDate: '', deadline: '', ...DEFAULT_SCHEDULE, ...watched }
  const errors = form.formState.errors

  const preview = useSchedulePreview(step === 2 ? toPreviewRequest(values, { startDate: values.startDate }) : null)

  function goToStep(next: 1 | 2) {
    setStep(next)
    window.setTimeout(() => document.getElementById('create-step-title')?.focus(), 0)
  }

  function checkDeadline(current: CreateValues): boolean {
    if (current.startDate && current.deadline && current.deadline < current.startDate) {
      form.setError('deadline', { type: 'validate', message: t('groupSetup.errDeadlineBeforeStart') })
      return false
    }
    return true
  }

  async function onNext() {
    const valid = await form.trigger(STEP1_FIELDS)
    if (valid && checkDeadline(form.getValues())) goToStep(2)
  }

  function setField(name: FieldName, value: string | boolean) {
    form.setValue(name, value as never, { shouldDirty: true, shouldValidate: form.formState.isSubmitted || name === 'agreed' })
  }

  const onSubmit = form.handleSubmit((data) => {
    if (create.isPending) return
    if (!checkDeadline(data)) {
      goToStep(1)
      return
    }
    if (!data.agreed) {
      form.setError('agreed', { type: 'validate', message: t('groupSetup.errAgreed') })
      return
    }
    const body: CreateGroupRequest = {
      name: data.name.trim(),
      courseName: data.courseName.trim() || null,
      startDate: data.startDate,
      deadline: data.deadline,
      schedule: toScheduleInput(data),
      scheduleAgreed: true,
    }
    create.mutate(body, {
      onSuccess: (response) => setCreated(response),
      onError: (error) => {
        if (!isApiError(error)) return
        let backToStep1 = error.code === ErrorCode.GROUP_DEADLINE_INVALID
        if (backToStep1) form.setError('deadline', { type: 'server', message: error.message })
        for (const fieldError of error.fieldErrors) {
          const name = (SCHEDULE_FIELD_MAP[fieldError.field] ?? fieldError.field) as FieldName
          if (name in form.getValues()) {
            form.setError(name, { type: 'server', message: fieldError.message })
            if (STEP1_FIELDS.includes(name)) backToStep1 = true
          }
        }
        if (backToStep1) goToStep(1)
      },
    })
  })

  const scheduleErrors = {
    weekday: errors.weekday?.message,
    cutoffTime: errors.cutoffTime?.message,
    timezone: errors.timezone?.message,
    window: errors.window?.message,
  }

  return (
    <>
      <UnsavedChangesGuard when={form.formState.isDirty && !created} />

      <ol className={styles.stepper} aria-label={t('groupSetup.stepsNav')}>
        {([1, 2] as const).map((n) => (
          <li key={n} className={cx(styles.step, step === n && styles.stepActive)} aria-current={step === n ? 'step' : undefined}>
            <span className={styles.stepNo} aria-hidden="true">
              {n}
            </span>
            <span>{n === 1 ? t('groupSetup.stepInfo') : t('groupSetup.stepSchedule')}</span>
          </li>
        ))}
      </ol>

      <form noValidate onSubmit={(event) => void onSubmit(event)} className={styles.card}>
        <h2 id="create-step-title" tabIndex={-1} className={styles.stepTitle}>
          <span className={styles.muted}>{t('groupSetup.stepLabel', { current: step, total: 2 })}</span>{' '}
          {step === 1 ? t('groupSetup.stepInfo') : t('groupSetup.stepSchedule')}
        </h2>

        {step === 1 ? (
          <div className={styles.fields}>
            <FormField label={t('groupSetup.name')} required hint={t('groupSetup.nameHint')} error={errors.name?.message}>
              <Input autoComplete="off" placeholder={t('groupSetup.namePlaceholder')} {...form.register('name')} />
            </FormField>
            <FormField label={t('groupSetup.courseName')} error={errors.courseName?.message}>
              <Input autoComplete="off" placeholder={t('groupSetup.courseNamePlaceholder')} {...form.register('courseName')} />
            </FormField>
            <div className={styles.row2}>
              <FormField label={t('groupSetup.startDate')} required error={errors.startDate?.message}>
                <Input type="date" {...form.register('startDate')} />
              </FormField>
              <FormField label={t('groupSetup.deadline')} required hint={t('groupSetup.deadlineHint')} error={errors.deadline?.message}>
                <Input type="date" min={values.startDate || undefined} {...form.register('deadline')} />
              </FormField>
            </div>
          </div>
        ) : (
          <div className={styles.fields}>
            <p className={styles.muted}>{t('groupSetup.scheduleIntro')}</p>
            <ScheduleFields
              values={values}
              errors={scheduleErrors}
              onChange={(patch) => {
                for (const [key, value] of Object.entries(patch)) setField(key as FieldName, value as string | boolean)
              }}
            />
            <SchedulePreviewPanel query={preview} timeZone={values.timezone} />
            <div className={styles.agreeField}>
              <label className={styles.checkbox}>
                <input
                  type="checkbox"
                  checked={values.agreed}
                  aria-invalid={errors.agreed ? true : undefined}
                  aria-describedby={errors.agreed ? 'agreed-error' : undefined}
                  onChange={(event) => setField('agreed', event.target.checked)}
                />
                <span>
                  <strong>{t('groupSetup.agreed')}</strong>
                  <span className={styles.muted}> {t('groupSetup.agreedHint')}</span>
                </span>
              </label>
              {errors.agreed ? (
                <p id="agreed-error" role="alert" className={styles.fieldError}>
                  {errors.agreed.message}
                </p>
              ) : null}
            </div>
          </div>
        )}

        {create.isError ? <InlineError error={create.error} title={t('groupSetup.createFailed')} /> : null}

        <div className={styles.actions}>
          {step === 2 ? (
            <Button variant="secondary" onClick={() => goToStep(1)} disabled={create.isPending}>
              {t('groupSetup.back')}
            </Button>
          ) : null}
          {step === 1 ? (
            <Button onClick={() => void onNext()}>{t('groupSetup.next')}</Button>
          ) : (
            <Button type="submit" loading={create.isPending}>
              {create.isPending ? t('groupSetup.creating') : t('groupSetup.createGroup')}
            </Button>
          )}
        </div>
      </form>

      {created ? (
        <Modal
          open
          size="md"
          onClose={() => navigate(paths.group(created.group.id), { replace: true })}
          title={t('groupSetup.successTitle', { name: created.group.name })}
          description={t('groupSetup.successDescription')}
          closeOnBackdrop={false}
          footer={
            <Button onClick={() => navigate(paths.group(created.group.id), { replace: true })}>{t('groupSetup.goToGroup')}</Button>
          }
        >
          <InviteShare
            code={created.invite.code}
            sharePath={created.invite.sharePath}
            expiresAt={created.invite.expiresAt}
            timeZone={created.group.timezone}
          />
        </Modal>
      ) : null}
    </>
  )
}
