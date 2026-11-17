import { useState } from 'react'
import { useCurrentUser } from '@/app/auth/authContext'
import { useGroup } from '@/app/group/groupContext'
import { zonedTimeToIso } from '@/lib/zonedTime'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useCreateTask } from '@/api/tasks'
import type { CheckpointCycle, Member, Difficulty } from '@/api/types'
import { Button, ConfirmDialog, InlineError, Modal, useToast } from '@/components'
import { useT } from '@/i18n'
import { PlanFormFields } from './PlanFormFields'
import {
  applyPlanServerErrors,
  buildPlanSchema,
  EMPTY_PLAN,
  resolvePlanTimes,
  selectableCycles,
  toCreateRequest,
  type PlanFormValues,
} from './planForm'

/** TL giao task sau cuộc họp (spec S04): lỗi hiển thị ngay trong modal, dữ liệu nhập được giữ lại. */
export function CreateTaskDialog({
  groupId,
  members,
  cycles,
  timeZone,
  onClose,
}: {
  groupId: string
  members: Member[]
  cycles: CheckpointCycle[]
  timeZone: string
  onClose: () => void
}) {
  const t = useT()
  const me = useCurrentUser()
  const { isLeader } = useGroup()
  const toast = useToast()
  const create = useCreateTask(groupId)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const options = selectableCycles(cycles)

  const form = useForm<PlanFormValues>({
    resolver: zodResolver(buildPlanSchema(t, { requireAssignee: true })),
    defaultValues: { ...EMPTY_PLAN, assigneeId: isLeader ? '' : me.userId },
    mode: 'onTouched',
  })

  const requestClose = () => (form.formState.isDirty && !create.isPending ? setConfirmDiscard(true) : onClose())

  const onSubmit = form.handleSubmit((values) => {
    if (create.isPending) return
    const resolved = resolvePlanTimes(values, timeZone, t)
    if (!resolved.ok) {
      form.setError(resolved.field, { type: 'validate', message: resolved.message })
      return
    }
    create.mutate(toCreateRequest(values, resolved.times), {
      onSuccess: (task) => {
        toast.success(t('tasks.create.success', { title: task.title }))
        onClose()
      },
      onError: (error) => {
        applyPlanServerErrors(error, form.setError)
      },
    })
  })

  const saveTaskDraft = () => {
    const values = form.getValues()
    if (values.title.trim().length < 3) { form.setError('title', { message: t('tasks.create.errTitle') }); return }
    if (!values.cycleId) { form.setError('cycleId', { message: t('tasks.create.errCycle') }); return }
    if (isLeader && !values.assigneeId) { form.setError('assigneeId', { message: t('tasks.create.errAssignee') }); return }
    create.mutate({ title: values.title.trim(), assigneeId: isLeader ? values.assigneeId : me.userId, cycleId: values.cycleId,
      description: values.description.trim() || undefined, expectedOutput: values.expectedOutput.trim() || undefined,
      startAt: values.startDate ? zonedTimeToIso(values.startDate, values.startTime, timeZone) ?? undefined : undefined,
      deadline: values.deadlineDate ? zonedTimeToIso(values.deadlineDate, values.deadlineTime, timeZone) ?? undefined : undefined,
      difficulty: values.difficulty ? values.difficulty as Difficulty : undefined },
      { onSuccess: () => { toast.success(t('workflow.draftSaved')); onClose() }, onError: (error) => applyPlanServerErrors(error, form.setError) })
  }

  return (
    <>
      <Modal
        open
        size="lg"
        title={isLeader ? t('tasks.create.title') : t('workflow.create')}
        description={t('tasks.create.description')}
        onClose={requestClose}
        closeOnBackdrop={false}
        footer={
          <>
            <Button variant="secondary" onClick={requestClose}>
              {t('common.cancel')}
            </Button>
            <Button variant="secondary" onClick={saveTaskDraft} disabled={create.isPending}>{t('workflow.saveDraft')}</Button>
            <Button type="submit" form="create-task-form" loading={create.isPending}>
              {create.isPending ? t('tasks.create.submitting') : t('tasks.create.submit')}
            </Button>
          </>
        }
      >
        {options.length === 0 ? <InlineError tone="warning" message={t('tasks.create.noCycle')} /> : null}
        <FormProvider {...form}>
          <form id="create-task-form" noValidate onSubmit={(event) => void onSubmit(event)}>
            <PlanFormFields cycles={options} members={members} timeZone={timeZone} showAssignee={isLeader} />
            {create.isError ? <InlineError error={create.error} title={t('tasks.create.failed')} /> : null}
          </form>
        </FormProvider>
      </Modal>
      <ConfirmDialog
        open={confirmDiscard}
        tone="danger"
        title={t('form.unsavedTitle')}
        description={t('form.unsavedDescription')}
        confirmLabel={t('form.unsavedLeave')}
        cancelLabel={t('form.unsavedStay')}
        onCancel={() => setConfirmDiscard(false)}
        onConfirm={() => {
          setConfirmDiscard(false)
          onClose()
        }}
      />
    </>
  )
}
