import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useCycles } from '@/api/schedule'
import { useCarryover } from '@/api/tasks'
import { ErrorCode, type CarryoverRequest, type TaskDetail } from '@/api/types'
import { isApiError } from '@/api/errors'
import { useGroup } from '@/app/group/groupContext'
import { Button, FormField, InlineError, Input, Modal, Select, Textarea, useToast } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { zonedTimeToIso } from '@/lib/zonedTime'
import styles from './TaskDetail.module.css'

interface CarryoverValues {
  targetCycleId: string
  deadlineDate: string
  deadlineTime: string
  scopeNote: string
  decisionReason: string
}

/**
 * TL `Chốt deadline xử lý tiếp` cho việc cần sửa/cập nhật hoặc thiếu kết quả (spec 0.5, S05):
 * mở vòng mới ở kỳ sau, giữ nguyên lịch sử vòng cũ và điểm. Task chỉ chờ duyệt không được chuyển.
 */
export function CarryoverDialog({ task, onClose }: { task: TaskDetail; onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const { groupId, timeZone } = useGroup()
  const cycles = useCycles(groupId)
  const carryover = useCarryover(groupId, task.id)
  const source = task.currentRound
  const candidates = (cycles.data?.items ?? [])
    .filter((cycle) => cycle.sequenceNo > source.cycle.sequenceNo && (cycle.status === 'SCHEDULED' || cycle.status === 'OPEN'))
    .sort((a, b) => a.sequenceNo - b.sequenceNo)
  const nextSequence = source.cycle.sequenceNo + 1

  const form = useForm<CarryoverValues>({
    resolver: zodResolver(
      z.object({
        targetCycleId: z.string().min(1, t('taskDetail.carryover.errCycle')),
        deadlineDate: z.string().min(1, t('taskDetail.carryover.errDeadline')),
        deadlineTime: z.string().min(1, t('taskDetail.carryover.errDeadline')),
        scopeNote: z.string().max(2000, t('taskDetail.carryover.errScope')),
        decisionReason: z.string().max(1000, t('taskDetail.carryover.errReason')),
      }),
    ),
    defaultValues: { targetCycleId: '', deadlineDate: '', deadlineTime: '20:00', scopeNote: '', decisionReason: '' },
    mode: 'onTouched',
  })
  const targetId = useWatch({ control: form.control, name: 'targetCycleId' })
  const target = candidates.find((cycle) => cycle.id === targetId)
  const reasonRequired = !!target && target.sequenceNo !== nextSequence
  const errors = form.formState.errors

  const onSubmit = form.handleSubmit((values) => {
    if (carryover.isPending) return
    const deadline = zonedTimeToIso(values.deadlineDate, values.deadlineTime, timeZone)
    if (!deadline) {
      form.setError('deadlineDate', { type: 'validate', message: t('taskDetail.carryover.errDeadline') })
      return
    }
    if (reasonRequired && values.decisionReason.trim().length < 5) {
      form.setError('decisionReason', { type: 'validate', message: t('taskDetail.carryover.errReason') })
      return
    }
    const body: CarryoverRequest & { roundId: string } = {
      roundId: source.id,
      targetCycleId: values.targetCycleId,
      deadline,
      scopeNote: values.scopeNote.trim() || null,
      decisionReason: values.decisionReason.trim() || null,
    }
    carryover.mutate(body, {
      onSuccess: () => {
        toast.success(t('taskDetail.carryover.done'))
        onClose()
      },
      onError: (error) => {
        if (!isApiError(error)) return
        if (error.code === ErrorCode.CARRYOVER_DEADLINE_INVALID) form.setError('deadlineDate', { type: 'server', message: error.message })
        if (error.code === ErrorCode.CARRYOVER_REASON_REQUIRED) form.setError('decisionReason', { type: 'server', message: error.message })
      },
    })
  })

  return (
    <Modal
      open
      size="md"
      title={t('taskDetail.carryover.title')}
      description={t('taskDetail.carryover.description')}
      onClose={() => !carryover.isPending && onClose()}
      closeOnBackdrop={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={carryover.isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="carryover-form" loading={carryover.isPending} disabled={candidates.length === 0}>
            {carryover.isPending ? t('taskDetail.carryover.submitting') : t('taskDetail.carryover.submit')}
          </Button>
        </>
      }
    >
      <form id="carryover-form" noValidate onSubmit={(event) => void onSubmit(event)} className={styles.formStack}>
        {source.reason !== 'INITIAL' || task.carryoverReason ? (
          <p className={styles.muted}>
            {t('taskDetail.carryover.reason', {
              reason: t(`taskDetail.rounds.reasons.${task.carryoverReason ?? source.reason}` as MessageKey),
            })}
          </p>
        ) : null}
        {candidates.length === 0 && !cycles.isPending ? <InlineError tone="warning" message={t('taskDetail.carryover.noCycle')} /> : null}
        <FormField label={t('taskDetail.carryover.cycle')} required error={errors.targetCycleId?.message}>
          <Select {...form.register('targetCycleId')}>
            <option value="">{t('taskDetail.carryover.cyclePlaceholder')}</option>
            {candidates.map((cycle) => (
              <option key={cycle.id} value={cycle.id}>
                {cycle.label}
              </option>
            ))}
          </Select>
        </FormField>
        <div className={styles.row2}>
          <FormField label={t('taskDetail.carryover.deadlineDate')} required hint={t('taskDetail.carryover.deadlineHint')} error={errors.deadlineDate?.message}>
            <Input type="date" {...form.register('deadlineDate')} />
          </FormField>
          <FormField label={t('taskDetail.carryover.deadlineTime')} required error={errors.deadlineTime?.message}>
            <Input type="time" {...form.register('deadlineTime')} />
          </FormField>
        </div>
        <FormField label={t('taskDetail.carryover.scope')} error={errors.scopeNote?.message}>
          <Textarea rows={3} {...form.register('scopeNote')} />
        </FormField>
        <FormField
          label={t('taskDetail.carryover.decisionReason')}
          required={reasonRequired}
          hint={t('taskDetail.carryover.decisionReasonHint')}
          error={errors.decisionReason?.message}
        >
          <Textarea rows={2} {...form.register('decisionReason')} />
        </FormField>
        {carryover.isError ? <InlineError error={carryover.error} title={t('taskDetail.carryover.failed')} /> : null}
      </form>
    </Modal>
  )
}
