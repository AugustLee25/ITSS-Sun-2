import { useState } from 'react'
import { FormProvider, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { hasStatus } from '@/api/errors'
import { useSubmitPlan, useUpdatePlan } from '@/api/tasks'
import type { CheckpointCycle, Member, PlanRevision, TaskDetail } from '@/api/types'
import { Button, Card, InlineError, UnsavedChangesGuard, useToast } from '@/components'
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
import styles from './TaskDetail.module.css'

/**
 * Soạn/sửa kế hoạch của chính mình ngay tại S05 (spec S05): `Lưu nháp` giữ DRAFT, `Gửi TL duyệt` → PENDING_APPROVAL.
 * Giữ dữ liệu khi lỗi; cảnh báo rời trang khi có thay đổi chưa lưu.
 */
export function PlanEditor({
  task,
  plan,
  groupId,
  timeZone,
  cycles,
  members,
  canChangeAssignee,
  onClose,
}: {
  task: TaskDetail
  plan: PlanRevision
  groupId: string
  timeZone: string
  cycles: CheckpointCycle[]
  members: Member[]
  canChangeAssignee: boolean
  onClose: () => void
}) {
  const t = useT()
  const toast = useToast()
  const update = useUpdatePlan(groupId, task.id)
  const submit = useSubmitPlan(groupId, task.id)
  const initial = planValuesFromRevision(plan, timeZone)
  const [conflict, setConflict] = useState(false)
  const form = useForm<PlanFormValues>({
    resolver: zodResolver(buildPlanSchema(t, { requireAssignee: false })),
    defaultValues: initial,
    mode: 'onTouched',
  })
  const options = selectableCycles(cycles, plan.proposedCycle.id)
  const busy = update.isPending || submit.isPending
  const error = update.error ?? submit.error

  /** Lưu thay đổi (nếu có) rồi trả version mới nhất của bản kế hoạch; null = lỗi đã được gắn vào form. */
  async function persist(values: PlanFormValues): Promise<number | null> {
    const changes = buildChangePayload(values, initial, timeZone, t)
    if (!changes.ok) {
      form.setError(changes.field, { type: 'validate', message: changes.message })
      return null
    }
    if (Object.keys(changes.payload).length === 0) return plan.version
    try {
      const result = await update.mutateAsync({ planId: plan.id, ...changes.payload, version: plan.version })
      form.reset(planValuesFromRevision(result.plan, timeZone))
      return result.plan.version
    } catch (err) {
      if (hasStatus(err, 409)) setConflict(true)
      applyPlanServerErrors(err, form.setError)
      return null
    }
  }

  const onSaveDraft = form.handleSubmit(async (values) => {
    if (busy) return
    const version = await persist(values)
    if (version !== null) {
      toast.success(t('taskDetail.plan.saved'))
      onClose()
    }
  })

  const onSend = form.handleSubmit(async (values) => {
    if (busy) return
    const version = await persist(values)
    if (version === null) return
    try {
      await submit.mutateAsync({ planId: plan.id, version })
      toast.success(t('taskDetail.plan.submitted'))
      form.reset(form.getValues())
      onClose()
    } catch (err) {
      if (hasStatus(err, 409)) setConflict(true)
      applyPlanServerErrors(err, form.setError)
    }
  })

  return (
    <Card as="section" aria-labelledby="plan-editor-title" className={styles.block}>
      <UnsavedChangesGuard when={form.formState.isDirty && !busy} />
      <h2 id="plan-editor-title" className={styles.blockTitle}>
        {t('taskDetail.plan.editTitle')}
      </h2>
      <p className={styles.muted}>{t('taskDetail.plan.editHint')}</p>
      <FormProvider {...form}>
        <form noValidate onSubmit={(event) => void onSaveDraft(event)} className={styles.formStack}>
          <PlanFormFields cycles={options} members={members} timeZone={timeZone} showAssignee={canChangeAssignee} />
          {conflict ? <InlineError tone="warning" message={t('taskDetail.conflict')} /> : null}
          {error && !conflict ? <InlineError error={error} title={t('taskDetail.plan.saveFailed')} /> : null}
          <div className={styles.buttonRow}>
            <Button type="submit" variant="secondary" loading={update.isPending && !submit.isPending} disabled={!form.formState.isDirty}>
              {update.isPending && !submit.isPending ? t('taskDetail.plan.savingDraft') : t('taskDetail.plan.saveDraft')}
            </Button>
            <Button onClick={() => void onSend()} loading={submit.isPending}>
              {submit.isPending ? t('taskDetail.plan.submitting') : t('taskDetail.plan.submit')}
            </Button>
            <Button variant="ghost" onClick={onClose} disabled={busy}>
              {t('taskDetail.plan.cancelEdit')}
            </Button>
          </div>
        </form>
      </FormProvider>
    </Card>
  )
}
