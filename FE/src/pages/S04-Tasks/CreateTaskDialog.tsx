import { useState } from 'react'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useCreateTask } from '@/api/tasks'
import type { CheckpointCycle, Member } from '@/api/types'
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
  const toast = useToast()
  const create = useCreateTask(groupId)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const options = selectableCycles(cycles)

  const form = useForm<PlanFormValues>({
    resolver: zodResolver(buildPlanSchema(t, { requireAssignee: true })),
    defaultValues: EMPTY_PLAN,
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

  return (
    <>
      <Modal
        open
        size="lg"
        title={t('tasks.create.title')}
        description={t('tasks.create.description')}
        onClose={requestClose}
        closeOnBackdrop={false}
        footer={
          <>
            <Button variant="secondary" onClick={requestClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" form="create-task-form" loading={create.isPending}>
              {create.isPending ? t('tasks.create.submitting') : t('tasks.create.submit')}
            </Button>
          </>
        }
      >
        {options.length === 0 ? <InlineError tone="warning" message={t('tasks.create.noCycle')} /> : null}
        <FormProvider {...form}>
          <form id="create-task-form" noValidate onSubmit={(event) => void onSubmit(event)}>
            <PlanFormFields cycles={options} members={members} timeZone={timeZone} showAssignee />
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
