import { useState } from 'react'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useProposeChange } from '@/api/tasks'
import type { CheckpointCycle, Member, PlanRevision, TaskDetail } from '@/api/types'
import { Button, ConfirmDialog, FormField, InlineError, Modal, Textarea, useToast } from '@/components'
import { useT } from '@/i18n'
import { PlanFormFields } from '@/pages/S04-Tasks/PlanFormFields'
import {
  applyPlanServerErrors,
  buildChangePayload,
  buildPlanSchema,
  planValuesFromRevision,
  selectableCycles,
  type PlanFormValues,
} from '@/pages/S04-Tasks/planForm'

type ProposeValues = PlanFormValues & { changeReason: string }

/** `Đề xuất thay đổi`: tạo bản sửa song song; bản đang có hiệu lực vẫn giữ nguyên cho tới khi TL duyệt (spec 0.4 #4). */
export function ProposeChangeDialog({
  task,
  base,
  groupId,
  timeZone,
  cycles,
  members,
  onClose,
}: {
  task: TaskDetail
  base: PlanRevision
  groupId: string
  timeZone: string
  cycles: CheckpointCycle[]
  members: Member[]
  onClose: () => void
}) {
  const t = useT()
  const toast = useToast()
  const propose = useProposeChange(groupId, task.id)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const initial = planValuesFromRevision(base, timeZone)
  const schema = buildPlanSchema(t, { requireAssignee: false }).extend({
    changeReason: z.string().trim().min(5, t('taskDetail.plan.errReason')).max(1000, t('taskDetail.plan.errReason')),
  })
  const form = useForm<ProposeValues>({
    resolver: zodResolver(schema),
    defaultValues: { ...initial, changeReason: '' },
    mode: 'onTouched',
  })
  const options = selectableCycles(cycles, base.proposedCycle.id)

  const requestClose = () => (form.formState.isDirty && !propose.isPending ? setConfirmDiscard(true) : onClose())

  function send(submitNow: boolean) {
    return form.handleSubmit((values) => {
      if (propose.isPending) return
      const changes = buildChangePayload(values, initial, timeZone, t)
      if (!changes.ok) {
        form.setError(changes.field, { type: 'validate', message: changes.message })
        return
      }
      if (Object.keys(changes.payload).length === 0) {
        form.setError('title', { type: 'validate', message: t('taskDetail.plan.errNoChange') })
        return
      }
      propose.mutate(
        { ...changes.payload, changeReason: values.changeReason.trim(), submitNow },
        {
          onSuccess: () => {
            toast.success(submitNow ? t('taskDetail.plan.proposeSent') : t('taskDetail.plan.proposeSavedDraft'))
            onClose()
          },
          onError: (error) => {
            applyPlanServerErrors(error, form.setError as never)
          },
        },
      )
    })()
  }

  return (
    <>
      <Modal
        open
        size="lg"
        title={t('taskDetail.plan.proposeTitle')}
        description={t('taskDetail.plan.proposeHint')}
        onClose={requestClose}
        closeOnBackdrop={false}
        footer={
          <>
            <Button variant="secondary" onClick={() => void send(false)} disabled={propose.isPending}>
              {t('taskDetail.plan.proposeSaveDraft')}
            </Button>
            <Button onClick={() => void send(true)} loading={propose.isPending}>
              {t('taskDetail.plan.proposeSend')}
            </Button>
          </>
        }
      >
        <FormProvider {...form}>
          <form noValidate onSubmit={(event) => event.preventDefault()}>
            <PlanFormFields cycles={options} members={members} timeZone={timeZone} showAssignee />
            <FormField label={t('taskDetail.plan.proposeReason')} required error={form.formState.errors.changeReason?.message}>
              <Textarea rows={3} {...form.register('changeReason')} />
            </FormField>
            {propose.isError ? <InlineError error={propose.error} title={t('taskDetail.plan.proposeFailed')} /> : null}
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
