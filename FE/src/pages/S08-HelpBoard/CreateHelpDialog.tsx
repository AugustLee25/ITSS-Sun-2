import { useEffect, useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { useCreateHelpRequest } from '@/api/help'
import { applyFieldErrors, isApiError } from '@/api/errors'
import { useTasks } from '@/api/tasks'
import { TaskStatus } from '@/api/types'
import { paths } from '@/app/routes'
import { Button, FormField, InlineError, Input, Modal, Select, Textarea, UnsavedChangesGuard, useToast } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import styles from './HelpBoard.module.css'

interface Values {
  taskId: string
  title: string
  description: string
  expectedOutput: string
  effort: string
  skillNotes: string
  privacyChecked: boolean
}

/** Chỉ task chưa nộp hoặc đang cần sửa/cập nhật mới có thể đăng (server kiểm tra lại). */
const POSTABLE = new Set<TaskStatus>([TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.FIX_REQUIRED, TaskStatus.UPDATE_REQUIRED])

/** S08 (chỉ TL): `+ Đăng yêu cầu` cho TOÀN BỘ task. Điểm P_t, deadline, độ khó do server điền. */
export function CreateHelpDialog({
  groupId,
  initialTaskId,
  supportRequestId,
  onClose,
}: {
  groupId: string
  initialTaskId?: string
  supportRequestId?: string
  onClose: () => void
}) {
  const t = useT()
  const f = useFormatters()
  const toast = useToast()
  const navigate = useNavigate()
  const create = useCreateHelpRequest(groupId)
  const tasks = useTasks(groupId, { size: 100 })
  const options = (tasks.data?.items ?? []).filter((task) => POSTABLE.has(task.taskStatus) && !task.hasOpenHelpRequest)
  const form = useForm<Values>({
    defaultValues: { taskId: initialTaskId ?? '', title: '', description: '', expectedOutput: '', effort: '', skillNotes: '', privacyChecked: false },
  })
  const [createdId, setCreatedId] = useState<string | null>(null)
  const errors = form.formState.errors
  const taskId = useWatch({ control: form.control, name: 'taskId' })
  const task = options.find((item) => item.id === taskId)

  useEffect(() => {
    if (createdId) navigate(paths.helpRequest(groupId, createdId))
  }, [createdId, groupId, navigate])

  const submit = form.handleSubmit((values) => {
    if (create.isPending || !task) return
    create.mutate(
      {
        taskId: task.id,
        beneficiaryId: task.currentExecutor.userId,
        supportRequestId: supportRequestId ?? null,
        title: values.title.trim(),
        description: values.description.trim(),
        expectedOutput: values.expectedOutput.trim() || null,
        estimatedEffortMinutes: values.effort ? Number(values.effort) : null,
        skillNotes: values.skillNotes.trim() || null,
        privacyChecked: true,
      },
      {
        onSuccess: (request) => {
          toast.success(t('helpBoard.posted'))
          setCreatedId(request.id)
        },
        onError: (error) => void applyFieldErrors(error, form.setError),
      },
    )
  })

  return (
    <Modal
      open
      size="lg"
      onClose={onClose}
      title={t('helpBoard.createTitle')}
      description={t('helpBoard.createDescription')}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="help-form" loading={create.isPending}>
            {t('helpBoard.post')}
          </Button>
        </>
      }
    >
      <UnsavedChangesGuard when={form.formState.isDirty && !createdId} />
      <form id="help-form" noValidate onSubmit={(event) => void submit(event)} className={styles.stack}>
        <FormField label={t('fields.task')} required error={errors.taskId?.message} hint={tasks.isError ? t('helpBoard.tasksFailed') : undefined}>
          <Select key={options.length} {...form.register('taskId', { required: t('helpBoard.errTask') })}>
            <option value="">{t('helpBoard.chooseTask')}</option>
            {options.map((item) => (
              <option key={item.id} value={item.id}>
                {item.title} — {item.currentExecutor.name}
              </option>
            ))}
          </Select>
        </FormField>
        {task ? (
          <p className={styles.preview} role="status">
            {task.points
              ? t('helpBoard.preview', { points: f.pointsLabel(task.points.amount), task: task.title, name: task.currentExecutor.name })
              : t('helpBoard.previewNoPoints', { name: task.currentExecutor.name })}
          </p>
        ) : null}
        <FormField label={t('helpBoard.fieldTitle')} required error={errors.title?.message}>
          <Input {...form.register('title', { required: t('helpBoard.errTitle'), minLength: { value: 3, message: t('helpBoard.errTitle') }, maxLength: { value: 120, message: t('helpBoard.errTitle') } })} />
        </FormField>
        <FormField label={t('helpBoard.fieldDescription')} required hint={t('helpBoard.descriptionHint')} error={errors.description?.message}>
          <Textarea rows={4} {...form.register('description', { required: t('helpBoard.errDescription'), minLength: { value: 10, message: t('helpBoard.errDescription') }, maxLength: { value: 2000, message: t('helpBoard.errDescription') } })} />
        </FormField>
        <FormField label={t('helpBoard.fieldOutput')} hint={t('helpBoard.outputHint')} error={errors.expectedOutput?.message}>
          <Textarea rows={2} {...form.register('expectedOutput')} />
        </FormField>
        <FormField label={t('helpBoard.fieldEffort')} hint={t('helpBoard.effortHint')} error={errors.effort?.message}>
          <Input type="number" inputMode="numeric" min={1} max={100000} {...form.register('effort', { min: { value: 1, message: t('helpBoard.errEffort') }, max: { value: 100000, message: t('helpBoard.errEffort') } })} />
        </FormField>
        <FormField label={t('helpBoard.fieldSkills')} error={errors.skillNotes?.message}>
          <Textarea rows={2} {...form.register('skillNotes', { maxLength: { value: 1000, message: t('helpBoard.errSkills') } })} />
        </FormField>
        <label className={styles.check}>
          <input type="checkbox" {...form.register('privacyChecked', { validate: (value) => value || t('helpBoard.errPrivacy') })} />
          <span>{t('helpBoard.privacy')}</span>
        </label>
        {errors.privacyChecked ? <p role="alert" className={styles.error}>{errors.privacyChecked.message}</p> : null}
        {create.isError && !(isApiError(create.error) && create.error.fieldErrors.length > 0) ? <InlineError error={create.error} title={t('helpBoard.postFailed')} /> : null}
      </form>
    </Modal>
  )
}
