import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { hasStatus } from '@/api/errors'
import { useMembers } from '@/api/groups'
import { useCycles } from '@/api/schedule'
import { useReviewPlan, useSubmitPlan, useWithdrawPlan } from '@/api/tasks'
import { PlanStatus, TaskAction, type PlanFieldChange, type PlanRevision, type TaskDetail } from '@/api/types'
import {
  Button,
  Card,
  ConfirmDialog,
  FormField,
  InlineError,
  MetaList,
  Modal,
  StatusChip,
  Textarea,
  useStatusChip,
  useToast,
} from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { useGroup } from '@/app/group/groupContext'
import { PlanEditor } from './PlanEditor'
import { ProposeChangeDialog } from './ProposeChangeDialog'
import styles from './TaskDetail.module.css'

function PlanSummary({ plan }: { plan: PlanRevision }) {
  const t = useT()
  const f = useFormatters()
  return (
    <MetaList
      items={[
        { label: t('taskDetail.assignee'), value: plan.assignee.name },
        { label: t('taskDetail.cycle'), value: plan.proposedCycle.label },
        plan.startAt ? { label: t('taskDetail.startAt'), value: <time dateTime={plan.startAt}>{f.dateTime(plan.startAt)}</time> } : null,
        plan.deadline ? { label: t('fields.deadline'), value: <time dateTime={plan.deadline}>{f.dateTime(plan.deadline)}</time> } : null,
        plan.difficulty ? { label: t('fields.difficulty'), value: t(`status.difficulty.${plan.difficulty}`) } : null,
      ]}
    />
  )
}

/** So sánh cũ/mới của bản sửa (server dựng `changes`; FE chỉ định dạng giờ khi server không kèm nhãn). */
function ChangesTable({ changes }: { changes: PlanFieldChange[] }) {
  const t = useT()
  const f = useFormatters()
  const show = (change: PlanFieldChange, which: 'old' | 'new') => {
    const label = which === 'old' ? change.oldLabel : change.newLabel
    const value = which === 'old' ? change.oldValue : change.newValue
    if (label) return label
    if (!value) return '—'
    return change.field === 'startAt' || change.field === 'deadline' ? f.dateTime(value) : value
  }
  if (changes.length === 0) return null
  return (
    <div>
      <h4 className={styles.subTitle}>{t('taskDetail.plan.changesTitle')}</h4>
      <table className={styles.changeTable}>
        <thead>
          <tr>
            <th scope="col" />
            <th scope="col">{t('taskDetail.plan.oldValue')}</th>
            <th scope="col">{t('taskDetail.plan.newValue')}</th>
          </tr>
        </thead>
        <tbody>
          {changes.map((change) => (
            <tr key={change.field}>
              <th scope="row">{t(`taskDetail.plan.fieldLabels.${change.field}` as MessageKey)}</th>
              <td data-label={t('taskDetail.plan.oldValue')}>{show(change, 'old')}</td>
              <td data-label={t('taskDetail.plan.newValue')} className={styles.changeNew}>
                {show(change, 'new')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function RequestChangesDialog({ plan, groupId, taskId, onClose }: { plan: PlanRevision; groupId: string; taskId: string; onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const review = useReviewPlan(groupId, taskId)
  const form = useForm<{ note: string }>({
    resolver: zodResolver(z.object({ note: z.string().trim().min(5, t('taskDetail.plan.errNote')).max(1000, t('taskDetail.plan.errNote')) })),
    defaultValues: { note: '' },
  })
  const onSubmit = form.handleSubmit(({ note }) => {
    if (review.isPending) return
    review.mutate(
      { planId: plan.id, decision: PlanStatus.CHANGES_REQUESTED, note: note.trim(), version: plan.version },
      {
        onSuccess: () => {
          toast.success(t('taskDetail.plan.changesRequested'))
          onClose()
        },
      },
    )
  })
  return (
    <Modal
      open
      size="md"
      title={t('taskDetail.plan.requestChangesTitle')}
      onClose={() => !review.isPending && onClose()}
      closeOnBackdrop={false}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={review.isPending}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="request-changes-form" loading={review.isPending}>
            {review.isPending ? t('taskDetail.plan.sendingRequest') : t('taskDetail.plan.sendRequest')}
          </Button>
        </>
      }
    >
      <form id="request-changes-form" noValidate onSubmit={(event) => void onSubmit(event)}>
        <FormField label={t('taskDetail.plan.requestChangesNote')} required hint={t('taskDetail.plan.requestChangesHint')} error={form.formState.errors.note?.message}>
          <Textarea rows={4} {...form.register('note')} />
        </FormField>
        {review.isError ? <InlineError error={review.error} title={t('taskDetail.plan.reviewFailed')} /> : null}
      </form>
    </Modal>
  )
}

/** Phiên bản kế hoạch: bản đang hiệu lực hiển thị mặc định, bản sửa chờ duyệt hiển thị song song (spec S05). */
export function PlanSection({ task }: { task: TaskDetail }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const toast = useToast()
  const { groupId, timeZone, isLeader } = useGroup()
  const cycles = useCycles(groupId)
  const members = useMembers(groupId)
  const submit = useSubmitPlan(groupId, task.id)
  const withdraw = useWithdrawPlan(groupId, task.id)
  const review = useReviewPlan(groupId, task.id)
  const [editing, setEditing] = useState(false)
  const [proposing, setProposing] = useState(false)
  const [requesting, setRequesting] = useState(false)
  const [confirmWithdraw, setConfirmWithdraw] = useState(false)

  const allowed = new Set(task.allowedActions)
  const effective = task.effectivePlan
  const pending = task.pendingRevision
  const hasEffective = !!effective
  const cycleList = cycles.data?.items ?? []
  const actionError = submit.error ?? review.error ?? withdraw.error
  const conflict = hasStatus(actionError, 409)

  if (!effective && !pending) return null

  return (
    <>
      {effective ? (
        <Card as="section" aria-labelledby="plan-effective" className={styles.block}>
          <div className={styles.blockHead}>
            <h2 id="plan-effective" className={styles.blockTitle}>
              {t('taskDetail.plan.effectiveTitle')}
            </h2>
            <StatusChip tone="success" size="sm" label={t('taskDetail.plan.revisionNo', { no: effective.revisionNo })} />
          </div>
          <PlanSummary plan={effective} />
          {effective.reviewedBy && effective.reviewedAt ? (
            <p className={styles.muted}>
              {t('taskDetail.plan.approvedBy', { name: effective.reviewedBy.name, time: f.dateTime(effective.reviewedAt) })}
            </p>
          ) : null}
          {allowed.has(TaskAction.PROPOSE_CHANGE) && !pending ? (
            <div className={styles.buttonRow}>
              <Button variant="secondary" onClick={() => setProposing(true)}>
                {t('taskDetail.plan.propose')}
              </Button>
            </div>
          ) : null}
        </Card>
      ) : null}

      {pending && !editing ? (
        <Card
          as="section"
          aria-labelledby="plan-pending"
          accent={pending.status === PlanStatus.PENDING_APPROVAL ? 'pending' : pending.status === PlanStatus.CHANGES_REQUESTED ? 'warning' : 'none'}
          className={styles.block}
        >
          <div className={styles.blockHead}>
            <h2 id="plan-pending" className={styles.blockTitle}>
              {hasEffective
                ? pending.status === PlanStatus.PENDING_APPROVAL
                  ? t('taskDetail.plan.pendingTitle')
                  : t('taskDetail.plan.pendingDraftTitle')
                : t('taskDetail.plan.draftTitle')}
            </h2>
            <StatusChip {...chip('plan', pending.status)} size="sm" />
          </div>
          {hasEffective ? <p className={styles.muted}>{t('taskDetail.plan.keepNotice')}</p> : null}
          {pending.submittedBy && pending.submittedAt ? (
            <p className={styles.muted}>
              {t('taskDetail.plan.submittedBy', { name: pending.submittedBy.name, time: f.dateTime(pending.submittedAt) })}
            </p>
          ) : null}
          {pending.changeReason ? (
            <p>
              <strong>{t('taskDetail.plan.changeReason')}:</strong> {pending.changeReason}
            </p>
          ) : null}
          {pending.status === PlanStatus.CHANGES_REQUESTED && pending.reviewNote ? (
            <InlineError
              tone="warning"
              title={t('taskDetail.plan.reviewNote')}
              message={`${pending.reviewNote}${pending.reviewedBy ? ` — ${pending.reviewedBy.name}` : ''}`}
            />
          ) : null}
          {hasEffective ? <ChangesTable changes={pending.changes} /> : <PlanSummary plan={pending} />}

          {conflict ? <InlineError tone="warning" message={t('taskDetail.conflict')} /> : null}
          {actionError && !conflict ? <InlineError error={actionError} title={t('taskDetail.plan.reviewFailed')} /> : null}

          <div className={styles.buttonRow}>
            {allowed.has(TaskAction.EDIT_PLAN) ? (
              <Button variant="secondary" onClick={() => setEditing(true)}>
                {t('taskDetail.plan.edit')}
              </Button>
            ) : null}
            {allowed.has(TaskAction.SUBMIT_PLAN) ? (
              <Button
                loading={submit.isPending}
                onClick={() =>
                  submit.mutate(
                    { planId: pending.id, version: pending.version },
                    { onSuccess: () => toast.success(t('taskDetail.plan.submitted')) },
                  )
                }
              >
                {submit.isPending ? t('taskDetail.plan.submitting') : t('taskDetail.plan.submit')}
              </Button>
            ) : null}
            {allowed.has(TaskAction.WITHDRAW_PLAN) ? (
              <Button variant="secondary" onClick={() => setConfirmWithdraw(true)}>
                {t('taskDetail.plan.withdraw')}
              </Button>
            ) : null}
            {allowed.has(TaskAction.REVIEW_PLAN) && pending.status === PlanStatus.PENDING_APPROVAL ? (
              <>
                <Button
                  loading={review.isPending}
                  onClick={() =>
                    review.mutate(
                      { planId: pending.id, decision: PlanStatus.APPROVED, version: pending.version },
                      { onSuccess: () => toast.success(t('taskDetail.plan.approved')) },
                    )
                  }
                >
                  {review.isPending ? t('taskDetail.plan.approving') : t('taskDetail.plan.approve')}
                </Button>
                <Button variant="secondary" onClick={() => setRequesting(true)} disabled={review.isPending}>
                  {t('taskDetail.plan.requestChanges')}
                </Button>
              </>
            ) : null}
          </div>
        </Card>
      ) : null}

      {editing && pending ? (
        <PlanEditor
          task={task}
          plan={pending}
          groupId={groupId}
          timeZone={timeZone}
          cycles={cycleList}
          members={members.data ?? []}
          canChangeAssignee={isLeader && !hasEffective}
          onClose={() => setEditing(false)}
        />
      ) : null}

      {proposing && effective ? (
        <ProposeChangeDialog
          task={task}
          base={effective}
          groupId={groupId}
          timeZone={timeZone}
          cycles={cycleList}
          members={members.data ?? []}
          onClose={() => setProposing(false)}
        />
      ) : null}

      {requesting && pending ? <RequestChangesDialog plan={pending} groupId={groupId} taskId={task.id} onClose={() => setRequesting(false)} /> : null}

      <ConfirmDialog
        open={confirmWithdraw}
        title={t('taskDetail.plan.withdrawTitle')}
        description={t('taskDetail.plan.withdrawDescription')}
        confirmLabel={t('taskDetail.plan.withdraw')}
        loading={withdraw.isPending}
        onCancel={() => setConfirmWithdraw(false)}
        onConfirm={() =>
          pending &&
          withdraw.mutate(
            { planId: pending.id, version: pending.version },
            {
              onSuccess: () => {
                setConfirmWithdraw(false)
                toast.success(t('taskDetail.plan.withdrawn'))
              },
              onError: () => setConfirmWithdraw(false),
            },
          )
        }
      />
    </>
  )
}
