import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useMyReport, useSaveReport, useSubmitReport } from '@/api/checkpointReport'
import { isApiError } from '@/api/errors'
import { useTask } from '@/api/tasks'
import { CheckpointTaskResult, ReportRiskLevel, type ReportItem, type ReportItemInput } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { useModalClose } from '@/app/modal'
import { paths } from '@/app/routes'
import {
  Button,
  EmptyState,
  FormField,
  InlineError,
  Input,
  LoadingSkeleton,
  Modal,
  StatusChip,
  Textarea,
  UnsavedChangesGuard,
  useStatusChip,
  useToast,
} from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { isoToZonedInput, zonedTimeToIso } from '@/lib/zonedTime'
import styles from './Checkpoint.module.css'

interface Draft {
  risk: ReportRiskLevel | null
  note: string
  date: string
  time: string
  completed: boolean
  links: string
  resultNote: string
  /** Đã được người dùng xác nhận (điền sẵn của server chưa tính là phản hồi). */
  confirmed: boolean
  noteOpen: boolean
}
type Drafts = Record<string, Draft>

const RISKS = [ReportRiskLevel.ON_TRACK, ReportRiskLevel.AT_RISK, ReportRiskLevel.NEEDS_LEAD_HELP] as const

const buildDrafts = (items: ReportItem[], timeZone: string): Drafts =>
  Object.fromEntries(
    items.map((item) => [
      item.taskId,
      {
        risk: item.riskLevel,
        note: item.note ?? '',
        date: isoToZonedInput(item.expectedCompletionAt, timeZone).date,
        time: isoToZonedInput(item.expectedCompletionAt, timeZone).time || '17:00',
        completed: !!item.submission || !!item.draftEvidence?.length,
        links: (item.draftEvidence ?? []).map((e) => e.url).join('\n'),
        resultNote: item.draftResultNote ?? '',
        confirmed: !item.prefilled && item.riskLevel !== null,
        noteOpen: !!item.note,
      },
    ]),
  )

/** Lỗi theo task của server: `items[<taskId>]` hoặc `items[<chỉ số>]`. */
function itemErrors(error: unknown, items: ReportItem[]): Record<string, string> {
  const out: Record<string, string> = {}
  if (!isApiError(error)) return out
  for (const fieldError of error.fieldErrors) {
    const key = /^items\[([^\]]+)\]/.exec(fieldError.field)?.[1]
    const item = key ? (items.find((i) => i.taskId === key) ?? items[Number(key)]) : undefined
    if (item) out[item.taskId] = fieldError.message
  }
  return out
}

/** S06 — Cập nhật checkpoint (route modal có link riêng; theo kỳ hoặc từ một task). */
export default function S06Checkpoint() {
  const t = useT()
  const close = useModalClose()
  const { groupId } = useGroup()
  const { cycleId: cycleParam, taskId } = useParams()
  const task = useTask(cycleParam ? '' : (taskId ?? ''))
  const cycleId = cycleParam ?? task.data?.checkpoint?.cycle.id
  const report = useMyReport(groupId, cycleId)

  if (report.data && cycleId) {
    return <ReportForm key={cycleId} report={report.data} groupId={groupId} cycleId={cycleId} focusTaskId={taskId} onClose={close} onReload={() => report.refetch()} />
  }
  const error = report.error ?? task.error
  return (
    <Modal open onClose={close} size="lg" title={t('checkpoint.title')} footer={<Button variant="secondary" onClick={close}>{t('common.close')}</Button>}>
      {error ? (
        <InlineError error={error} title={t('checkpoint.loadFailed')} onRetry={() => void (report.error ? report.refetch() : task.refetch())} />
      ) : task.data && !cycleId ? (
        <EmptyState icon="calendar" title={t('checkpoint.noCycleTitle')} description={t('checkpoint.noCycleDescription')} />
      ) : (
        <LoadingSkeleton variant="list" count={2} />
      )}
    </Modal>
  )
}

function ReportForm({
  report,
  groupId,
  cycleId,
  focusTaskId,
  onClose,
  onReload,
}: {
  report: NonNullable<ReturnType<typeof useMyReport>['data']>
  groupId: string
  cycleId: string
  focusTaskId?: string
  onClose: () => void
  onReload: () => Promise<{ data?: typeof report }>
}) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const toast = useToast()
  const save = useSaveReport(groupId, cycleId)
  const submit = useSubmitReport(groupId, cycleId)
  const { items, canEdit, cycle } = report

  const [drafts, setDrafts] = useState(() => buildDrafts(items, f.timeZone))
  const [baseline, setBaseline] = useState(() => JSON.stringify(drafts))
  const [checked, setChecked] = useState(false)
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({})
  const [done, setDone] = useState(false)
  const dirty = JSON.stringify(drafts) !== baseline
  const patch = (taskId: string, change: Partial<Draft>) => {
    setDrafts((d) => ({ ...d, [taskId]: { ...(d[taskId] ?? buildDrafts(items, f.timeZone)[taskId]), ...change } }))
    setLocalErrors((errors) => { const next = { ...errors }; delete next[taskId]; return next })
  }

  useEffect(() => {
    if (done) onClose()
  }, [done, onClose])
  useEffect(() => {
    if (focusTaskId) document.getElementById(`item-${focusTaskId}`)?.scrollIntoView?.({ block: 'center' })
  }, [focusTaskId])

  const answered = (item: ReportItem) => drafts[item.taskId]?.confirmed && drafts[item.taskId]?.risk
  const missing = items.filter((item) => !answered(item))
  const submittedItems = items.filter((item) => item.submission)
  const otherItems = items.filter((item) => !item.submission)
  const unconfirmedSubmitted = submittedItems.filter((item) => !drafts[item.taskId]?.confirmed)

  const getDraft = (item: ReportItem) => drafts[item.taskId] ?? buildDrafts([item], f.timeZone)[item.taskId]
  const toInput = (item: ReportItem): ReportItemInput => {
    const d = getDraft(item)
    return {
      taskId: item.taskId,
      roundId: item.roundId,
      planRevisionId: item.currentPlanRevisionId ?? item.planRevisionId ?? undefined,
      submissionId: item.currentSubmissionId ?? item.submission?.id ?? null,
      declaredResult: d.completed ? CheckpointTaskResult.RESULT_SUBMITTED : CheckpointTaskResult.RESULT_MISSING,
      evidence: d.completed ? d.links.split('\n').map((url) => url.trim()).filter(Boolean).map((url) => ({ type: 'URL' as const, url })) : [],
      resultNote: d.resultNote.trim() || null,
      riskLevel: d.completed ? ReportRiskLevel.ON_TRACK : d.risk ?? ReportRiskLevel.ON_TRACK,
      note: d.note.trim() || null,
      expectedCompletionAt: !d.completed && d.date ? zonedTimeToIso(d.date, d.time, f.timeZone) : null,
    }
  }

  const confirmSubmitted = () =>
    setDrafts((d) => {
      const next = { ...d }
      for (const item of unconfirmedSubmitted) next[item.taskId] = { ...getDraft(item), confirmed: true, risk: getDraft(item).risk ?? ReportRiskLevel.ON_TRACK }
      return next
    })

  const saveDraft = () => {
    if (save.isPending) return
    save.mutate(
      { items: items.filter(answered).map(toInput), version: report.version },
      {
        onSuccess: () => {
          setBaseline(JSON.stringify(drafts))
          toast.success(t('checkpoint.draftSaved'))
        },
      },
    )
  }

  const send = () => {
    if (submit.isPending) return
    if (missing.length > 0) {
      setChecked(true)
      document.getElementById(`item-${missing[0].taskId}`)?.scrollIntoView?.({ block: 'center' })
      return
    }
    const invalid: Record<string, string> = {}
    for (const item of items) {
      const d = getDraft(item)
      if (!d.completed && (!d.note.trim() || !d.date || !zonedTimeToIso(d.date, d.time, f.timeZone))) invalid[item.taskId] = t('workflow.required')
      if (d.completed && !item.submission && !d.links.trim()) invalid[item.taskId] = t('workflow.evidenceRequired')
    }
    setLocalErrors(invalid)
    if (Object.keys(invalid).length) return
    submit.mutate(
      { items: items.map(toInput), version: report.version },
      {
        onSuccess: () => {
          toast.success(t('checkpoint.submitted'))
          setDone(true)
        },
      },
    )
  }

  const reload = async () => {
    save.reset()
    submit.reset()
    const fresh = (await onReload()).data
    if (fresh) {
      const next = buildDrafts(fresh.items, f.timeZone)
      setDrafts(next)
      setBaseline(JSON.stringify(next))
    }
  }

  const error = submit.error ?? save.error
  const perItem = { ...itemErrors(error, items), ...localErrors }
  const conflict = isApiError(error) && error.code === 'VERSION_CONFLICT'
  const showGlobalError = error && !(Object.keys(perItem).length > 0 && !conflict)

  const renderItem = (item: ReportItem) => {
    const d = getDraft(item)
    const itemError = perItem[item.taskId] ?? (checked && !answered(item) ? t('checkpoint.itemUnanswered') : undefined)
    const noteVisible = !d.completed || d.noteOpen || d.risk !== ReportRiskLevel.ON_TRACK
    return (
      <li key={item.taskId} id={`item-${item.taskId}`} className={`${styles.item} ${itemError ? styles.itemInvalid : ''}`}>
        <div className={styles.itemHead}>
          <div>
            <h4 className={styles.itemTitle}>{item.title}</h4>
            <p className={styles.muted}>
              {t('checkpoint.round', { no: item.roundNo })} · {t('fields.deadline')}: <time dateTime={item.deadline}>{f.dateTime(item.deadline)}</time>
              {item.difficulty ? ` · ${t(`status.difficulty.${item.difficulty}`)}` : ''}
            </p>
          </div>
          <div className={styles.chips}>
            <StatusChip {...chip('task', item.taskStatus)} size="sm" />
            {d.confirmed && d.risk ? (
              <StatusChip tone="success" label={t('checkpoint.answered')} size="sm" />
            ) : (
              <StatusChip tone="muted" label={t('checkpoint.notAnswered')} size="sm" />
            )}
          </div>
        </div>
        {item.expectedOutput ? <p className={styles.muted}>{t('checkpoint.output', { output: item.expectedOutput })}</p> : null}
        {item.planReferenceKnown === false ? <p className={styles.muted}>{t('workflow.planUnknown')}</p> : null}
        <p>
          <strong>{t('checkpoint.declared')}: </strong>
          {d.completed ? t('checkpoint.resultSubmitted') : t('checkpoint.resultMissing')}
          <span className={styles.muted}> · {item.timelinessLabel}</span>
        </p>
        {item.submission ? (
          <div className={styles.muted}>
            {t('checkpoint.submittedAt', { at: f.dateTime(item.submission.submittedAt) })} · <span>{item.submission.reviewDecision === 'ACCEPTED' ? t('workflow.accepted') : item.submission.reviewDecision ? t(`overview.reviewDecision.${item.submission.reviewDecision}`) : t('workflow.pending')}</span>
            <ul className={styles.links}>
              {item.submission.evidence.map((evidence) => (
                <li key={evidence.url}>
                  <a href={evidence.url} target="_blank" rel="noreferrer">
                    {evidence.label || evidence.url}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
        <fieldset className={styles.radios} disabled={!canEdit}>
          <legend>{t('checkpoint.declared')}</legend>
          <label><input type="radio" name={'result-' + item.taskId} checked={d.completed} onChange={() => patch(item.taskId, { completed: true, confirmed: true, risk: ReportRiskLevel.ON_TRACK })} />{t('workflow.result')}</label>
          <label><input type="radio" name={'result-' + item.taskId} checked={!d.completed} disabled={!!item.submission} onChange={() => patch(item.taskId, { completed: false, confirmed: true })} />{t('workflow.missing')}</label>
        </fieldset>
        {d.completed && canEdit ? <>
          <FormField label={t('workflow.evidence')} hint={t('workflow.evidenceHint')} required={!item.submission}>
            <Textarea rows={2} value={d.links} onChange={(e) => patch(item.taskId, { links: e.target.value, confirmed: true })} />
          </FormField>
          <FormField label={t('workflow.resultNote')}>
            <Textarea rows={2} maxLength={2000} value={d.resultNote} onChange={(e) => patch(item.taskId, { resultNote: e.target.value, confirmed: true })} />
          </FormField>
        </> : null}
        <fieldset className={styles.radios} disabled={!canEdit || d.completed}>
          <legend>{t('checkpoint.risk')}</legend>
          {RISKS.map((risk) => (
            <label key={risk} className={styles.radio}>
              <input
                type="radio"
                name={`risk-${item.taskId}`}
                checked={d.risk === risk}
                onChange={() => patch(item.taskId, { risk, confirmed: true })}
              />
              {t(`status.reportRisk.${risk}`)}
            </label>
          ))}
        </fieldset>
        {item.canReportDifficulty ? (
          <p className={styles.muted}>
            {d.risk === ReportRiskLevel.NEEDS_LEAD_HELP && canEdit && !d.completed ? (
              <>
                {t('checkpoint.needHelpHint')}{' '}
              </>
            ) : null}
            <Link to={paths.taskDifficulty(groupId, item.taskId)} preventScrollReset>
              {t('checkpoint.needHelp')}
            </Link>
          </p>
        ) : null}
        {noteVisible ? (
          <>
            <FormField label={d.completed ? t('checkpoint.note') : t('workflow.reason')} required={!d.completed}>
              <Textarea rows={2} maxLength={1000} value={d.note} disabled={!canEdit} onChange={(e) => patch(item.taskId, { note: e.target.value, confirmed: true })} />
            </FormField>
            {!d.completed ? (
              <FormField label={t('workflow.expected')} hint={t('checkpoint.expectedHint')} required>
                {(controlProps) => <div>
                <Input {...controlProps} type="date" value={d.date} disabled={!canEdit} onChange={(e) => patch(item.taskId, { date: e.target.value, confirmed: true })} />
                <Input type="time" aria-label={t('workflow.expectedTime')} value={d.time} disabled={!canEdit} onChange={(e) => patch(item.taskId, { time: e.target.value, confirmed: true })} />
                {d.date && zonedTimeToIso(d.date, d.time, f.timeZone) ? <p>{Date.parse(zonedTimeToIso(d.date, d.time, f.timeZone)!) > Date.parse(item.deadline) ? t('workflow.extension') : t('workflow.inTime')}</p> : null}
                </div>}
              </FormField>
            ) : null}
          </>
        ) : canEdit ? (
          <div>
            <Button variant="ghost" onClick={() => patch(item.taskId, { noteOpen: true })}>
              {t('checkpoint.noteAdd')}
            </Button>
          </div>
        ) : null}
        {itemError ? <p role="alert" className={styles.error}>{itemError}</p> : null}
      </li>
    )
  }

  return (
    <Modal
      open
      size="lg"
      onClose={onClose}
      title={t('checkpoint.title')}
      footer={
        canEdit ? (
          <>
            <Button variant="secondary" onClick={onClose}>
              {t('common.close')}
            </Button>
            <Button variant="secondary" loading={save.isPending} disabled={items.length === 0} onClick={saveDraft}>
              {t('checkpoint.saveDraft')}
            </Button>
            <Button loading={submit.isPending} disabled={items.length === 0} onClick={send}>
              {report.status === 'SUBMITTED' ? t('workflow.update') : t('checkpoint.submit')}
            </Button>
          </>
        ) : (
          <Button variant="secondary" onClick={onClose}>
            {t('common.close')}
          </Button>
        )
      }
    >
      <UnsavedChangesGuard when={dirty && !done} />
      <div className={styles.stack}>
        <div className={styles.summary}>
          <strong>{cycle.label}</strong>
          <StatusChip {...chip('report', report.state)} size="sm" />
          <span>{cycle.phaseLabel}</span>
          <span>
            {t('checkpoint.window', { from: f.dateTime(cycle.opensAt), to: f.dateTime(cycle.closesAt) })} ({cycle.timezone})
          </span>
          {report.revisionNo ? <span>{t('checkpoint.revision', { no: report.revisionNo })}</span> : null}
          {report.submittedLabel ? <strong>{report.submittedLabel}</strong> : null}
          {report.submittedAt ? <span>{t('checkpoint.sentAt', { at: f.dateTime(report.submittedAt) })}</span> : null}
        </div>

        {!canEdit && report.lockReason ? <InlineError tone="warning" message={report.lockReason.message} /> : null}
        {showGlobalError ? (
          <InlineError error={error} title={t('checkpoint.saveFailed')} onRetry={conflict ? () => void reload() : undefined} retryLabel={t('errors.conflictReload')} />
        ) : null}

        {items.length === 0 ? (
          <EmptyState compact icon="check" title={t('checkpoint.noTasksTitle')} description={t('checkpoint.noTasksDescription')} />
        ) : (
          <>
            <p role="status" aria-live="polite">
              {t('checkpoint.progress', { done: items.length - missing.length, total: items.length })}
              {checked && missing.length > 0 ? ` ${t('checkpoint.incomplete', { count: missing.length })}` : ''}
            </p>
            {submittedItems.length > 0 ? (
              <section className={styles.section} aria-labelledby="cp-submitted">
                <div className={styles.sectionHead}>
                  <h3 id="cp-submitted" className={styles.sectionTitle}>
                    {t('checkpoint.submittedTitle', { count: submittedItems.length })}
                  </h3>
                  {canEdit && unconfirmedSubmitted.length > 0 ? (
                    <Button icon="check" onClick={confirmSubmitted}>
                      {t('checkpoint.batchConfirm', { count: unconfirmedSubmitted.length })}
                    </Button>
                  ) : null}
                </div>
                <ul className={styles.section}>{submittedItems.map(renderItem)}</ul>
              </section>
            ) : null}
            {otherItems.length > 0 ? (
              <section className={styles.section} aria-labelledby="cp-open">
                <h3 id="cp-open" className={styles.sectionTitle}>
                  {t('checkpoint.openTitle', { count: otherItems.length })}
                </h3>
                <ul className={styles.section}>{otherItems.map(renderItem)}</ul>
              </section>
            ) : null}
          </>
        )}

        {report.backlog.length > 0 ? (
          <section className={styles.section} aria-labelledby="cp-backlog">
            <h3 id="cp-backlog" className={styles.sectionTitle}>
              {t('checkpoint.backlogTitle')}
            </h3>
            <p className={styles.muted}>{t('checkpoint.backlogHint')}</p>
            <ul className={styles.links}>
              {report.backlog.map((entry) => (
                <li key={entry.roundId}>
                  <Link to={paths.task(groupId, entry.taskId)}>{entry.title}</Link> — {entry.message} ({entry.fromCycle.label})
                </li>
              ))}
            </ul>
          </section>
        ) : null}
      </div>
    </Modal>
  )
}
