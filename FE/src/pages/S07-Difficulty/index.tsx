import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { applyFieldErrors, hasStatus, isApiError } from '@/api/errors'
import {
  useCreateRiskReport,
  useCreateSupportRequest,
  useDecideSupportRequest,
  useResolveRisk,
  useRiskReports,
  useSupportRequest,
} from '@/api/support'
import { useTask } from '@/api/tasks'
import {
  ObstacleType,
  RiskReportStatus,
  RiskType,
  SupportRequestAction,
  SupportRequestLeadAction,
  type SupportRequest,
} from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { useModalClose } from '@/app/modal'
import { paths } from '@/app/routes'
import {
  Button,
  ButtonLink,
  FormField,
  InlineError,
  Input,
  LoadingSkeleton,
  MetaList,
  Modal,
  Select,
  StatusChip,
  Textarea,
  UnsavedChangesGuard,
  useStatusChip,
  useToast,
} from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { formatCalendarDate } from '@/lib/zonedTime'
import styles from './Difficulty.module.css'

/** S07 — Báo khó khăn (thành viên, `tasks/:taskId/difficulty`) hoặc xem/xử lý đề nghị (`difficulty/:supportRequestId`). */
export default function S07Difficulty() {
  const close = useModalClose()
  const { supportRequestId } = useParams()
  return supportRequestId ? <RequestView id={supportRequestId} onClose={close} /> : <NewRequest onClose={close} />
}

interface NewValues {
  obstacleType: ObstacleType
  description: string
  neededSupport: string
  impact: string
  expectedDate: string
}

function NewRequest({ onClose }: { onClose: () => void }) {
  const t = useT()
  const toast = useToast()
  const navigate = useNavigate()
  const { groupId } = useGroup()
  const { taskId = '' } = useParams()
  const task = useTask(taskId)
  const create = useCreateSupportRequest(groupId, taskId)
  const form = useForm<NewValues>({ defaultValues: { obstacleType: ObstacleType.TECHNICAL, description: '', neededSupport: '', impact: '', expectedDate: '' } })
  const [sentId, setSentId] = useState<string | null>(null)
  const errors = form.formState.errors

  // Điều hướng sau khi Guard đã được tắt (state đã commit).
  useEffect(() => {
    if (sentId) navigate(paths.difficulty(groupId, sentId), { replace: true })
  }, [sentId, groupId, navigate])

  const submit = form.handleSubmit((values) => {
    if (create.isPending) return
    create.mutate(
      {
        obstacleType: values.obstacleType,
        description: values.description.trim(),
        neededSupport: values.neededSupport.trim(),
        impact: values.impact.trim() || null,
        expectedDate: values.expectedDate || null,
        roundId: task.data?.currentRound.id ?? null,
      },
      {
        onSuccess: (request) => {
          toast.success(t('support.sent'))
          setSentId(request.id)
        },
        onError: (error) => void applyFieldErrors(error, form.setError),
      },
    )
  })

  return (
    <Modal
      open
      onClose={onClose}
      title={t('support.newTitle')}
      description={t('support.newDescription')}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" form="support-form" loading={create.isPending}>
            {t('support.send')}
          </Button>
        </>
      }
    >
      <UnsavedChangesGuard when={form.formState.isDirty && !sentId} />
      {task.isLoading ? <LoadingSkeleton variant="text" /> : null}
      {task.isError ? <InlineError error={task.error} onRetry={() => void task.refetch()} /> : null}
      {task.data ? (
        <form id="support-form" noValidate onSubmit={(event) => void submit(event)} className={styles.stack}>
          <p>
            <strong>{t('fields.task')}: </strong>
            {task.data.title}
          </p>
          <FormField label={t('support.obstacle')} required>
            <Select {...form.register('obstacleType')}>
              {Object.values(ObstacleType).map((type) => (
                <option key={type} value={type}>
                  {t(`support.obstacles.${type}`)}
                </option>
              ))}
            </Select>
          </FormField>
          <FormField label={t('support.description')} required error={errors.description?.message}>
            <Textarea rows={3} {...form.register('description', { required: t('support.errDescription'), minLength: { value: 10, message: t('support.errDescription') }, maxLength: { value: 2000, message: t('support.errDescription') } })} />
          </FormField>
          <FormField label={t('support.needed')} required error={errors.neededSupport?.message}>
            <Textarea rows={2} {...form.register('neededSupport', { required: t('support.errNeeded'), minLength: { value: 5, message: t('support.errNeeded') }, maxLength: { value: 1000, message: t('support.errNeeded') } })} />
          </FormField>
          <FormField label={t('support.impact')} error={errors.impact?.message}>
            <Textarea rows={2} {...form.register('impact', { maxLength: { value: 1000, message: t('support.errImpact') } })} />
          </FormField>
          <FormField label={t('support.expectedDate')} hint={t('support.expectedHint')}>
            <Input type="date" {...form.register('expectedDate')} />
          </FormField>
          <p className={styles.muted}>{t('support.privacy')}</p>
          {create.isError && !(isApiError(create.error) && create.error.fieldErrors.length > 0) ? <InlineError error={create.error} title={t('support.sendFailed')} /> : null}
        </form>
      ) : null}
    </Modal>
  )
}

function RequestView({ id, onClose }: { id: string; onClose: () => void }) {
  const t = useT()
  const { groupId, isLeader } = useGroup()
  const request = useSupportRequest(id)
  const data = request.data
  return (
    <Modal
      open
      onClose={onClose}
      size="lg"
      title={isLeader ? t('support.leadTitle') : t('support.viewTitle')}
      footer={
        <Button variant="secondary" onClick={onClose}>
          {t('common.close')}
        </Button>
      }
    >
      {request.isLoading ? <LoadingSkeleton variant="list" count={2} /> : null}
      {request.isError ? (
        <InlineError
          error={request.error}
          title={hasStatus(request.error, 403, 404) ? t('support.noAccess') : t('support.loadFailed')}
          onRetry={hasStatus(request.error, 403, 404) ? undefined : () => void request.refetch()}
        />
      ) : null}
      {data ? (
        <div className={styles.stack}>
          <Details request={data} groupId={groupId} />
          {isLeader ? <LeadPanel request={data} groupId={groupId} /> : <SenderPanel request={data} groupId={groupId} />}
          {isLeader ? <RiskSection request={data} groupId={groupId} /> : null}
        </div>
      ) : null}
    </Modal>
  )
}

function Details({ request, groupId }: { request: SupportRequest; groupId: string }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  return (
    <section className={styles.stack} aria-label={t('support.detailsLabel')}>
      <div className={styles.row}>
        <h3 className={styles.title}>
          <Link to={paths.task(groupId, request.taskId)}>{request.taskTitle}</Link>
        </h3>
        <StatusChip {...chip('support', request.status)} />
      </div>
      <MetaList
        items={[
          { label: t('support.sender'), value: request.sender.name },
          { label: t('support.obstacle'), value: t(`support.obstacles.${request.obstacleType}`) },
          { label: t('fields.at'), value: <time dateTime={request.createdAt}>{f.dateTime(request.createdAt)}</time> },
          { label: t('support.expectedDate'), value: request.expectedDate ? formatCalendarDate(request.expectedDate) : null },
        ]}
      />
      <div>
        <h4>{t('support.description')}</h4>
        <p className={styles.pre}>{request.description}</p>
      </div>
      <div>
        <h4>{t('support.needed')}</h4>
        <p className={styles.pre}>{request.neededSupport}</p>
      </div>
      {request.impact ? (
        <div>
          <h4>{t('support.impact')}</h4>
          <p className={styles.pre}>{request.impact}</p>
        </div>
      ) : null}
    </section>
  )
}

/** Người gửi theo dõi tình trạng và phản hồi của TL. */
function SenderPanel({ request, groupId }: { request: SupportRequest; groupId: string }) {
  const t = useT()
  const published = request.publishedHelpRequestIds[0]
  return (
    <section className={styles.stack} aria-label={t('support.responseTitle')}>
      <h3 className={styles.title}>{t('support.responseTitle')}</h3>
      {request.leadResponse ? <p className={styles.pre}>{request.leadResponse}</p> : <p className={styles.muted}>{t('support.waiting')}</p>}
      {published ? (
        <ButtonLink to={paths.helpRequest(groupId, published)} variant="secondary">
          {t('support.viewPublished')}
        </ButtonLink>
      ) : null}
    </section>
  )
}

interface DecisionValues {
  response: string
}

function LeadPanel({ request, groupId }: { request: SupportRequest; groupId: string }) {
  const t = useT()
  const toast = useToast()
  const decide = useDecideSupportRequest(groupId, request.id)
  const form = useForm<DecisionValues>({ defaultValues: { response: '' } })
  const canDecide = request.allowedActions.includes(SupportRequestAction.DECIDE)
  const canPublish = request.allowedActions.includes(SupportRequestAction.PUBLISH_HELP)
  const send = (action: SupportRequestLeadAction) =>
    form.handleSubmit((values) => {
      if (decide.isPending) return
      decide.mutate(
        { action, response: values.response.trim(), version: request.version },
        { onSuccess: () => { toast.success(t('support.decided')); form.reset() } },
      )
    })()
  const publishHref = `${paths.help(groupId)}?create=1&taskId=${encodeURIComponent(request.taskId)}&supportRequestId=${encodeURIComponent(request.id)}`

  return (
    <section className={styles.stack} aria-label={t('support.leadActions')}>
      <h3 className={styles.title}>{t('support.leadActions')}</h3>
      {request.leadResponse ? <p className={styles.pre}>{t('support.yourResponse', { text: request.leadResponse })}</p> : null}
      {canDecide ? (
        <form noValidate onSubmit={(event) => event.preventDefault()} className={styles.stack}>
          <FormField label={t('support.response')} required error={form.formState.errors.response?.message}>
            <Textarea rows={3} maxLength={2000} {...form.register('response', { required: t('support.errResponse') })} />
          </FormField>
          {decide.isError ? <InlineError error={decide.error} /> : null}
          <div className={styles.actions}>
            <Button loading={decide.isPending} onClick={() => void send(SupportRequestLeadAction.RESOLVED_PRIVATELY)}>
              {t('support.resolvedPrivately')}
            </Button>
            <Button variant="secondary" loading={decide.isPending} onClick={() => void send(SupportRequestLeadAction.NEED_MORE_DISCUSSION)}>
              {t('support.needMore')}
            </Button>
            {canPublish ? (
              <ButtonLink to={publishHref} variant="secondary">
                {t('support.publish')}
              </ButtonLink>
            ) : null}
          </div>
          {canPublish ? <p className={styles.muted}>{t('support.publishHint')}</p> : null}
        </form>
      ) : (
        <p className={styles.muted}>{t('support.closed')}</p>
      )}
    </section>
  )
}

interface RiskValues {
  riskType: RiskType
  publicSummary: string
}

/** Risk chung: chỉ `publicSummary` được chia sẻ cho nhóm; TL tự viết, không chép nội dung riêng. */
function RiskSection({ request, groupId }: { request: SupportRequest; groupId: string }) {
  const t = useT()
  const toast = useToast()
  const risks = useRiskReports(groupId, { taskId: request.taskId })
  const create = useCreateRiskReport(groupId)
  const resolve = useResolveRisk(groupId)
  const form = useForm<RiskValues>({ defaultValues: { riskType: RiskType.DELAY_RISK, publicSummary: '' } })
  const submit = form.handleSubmit((values) => {
    if (create.isPending) return
    create.mutate(
      { riskType: values.riskType, taskId: request.taskId, subjectUserId: request.sender.userId, publicSummary: values.publicSummary.trim() },
      { onSuccess: () => { toast.success(t('support.riskSaved')); form.reset() } },
    )
  })
  return (
    <section className={styles.stack} aria-label={t('support.riskTitle')}>
      <h3 className={styles.title}>{t('support.riskTitle')}</h3>
      <p className={styles.muted}>{t('support.riskHint')}</p>
      {risks.data?.items.map((risk) => (
        <div key={risk.id} className={styles.row}>
          <span>
            <strong>{t(`support.risks.${risk.riskType}`)}</strong> — {risk.publicSummary}{' '}
            <span className={styles.muted}>({t(`support.riskStatus.${risk.status}`)})</span>
          </span>
          {risk.status !== RiskReportStatus.RESOLVED ? (
            <Button variant="ghost" loading={resolve.isPending} onClick={() => resolve.mutate({ id: risk.id, version: risk.version })}>
              {t('support.riskResolve')}
            </Button>
          ) : null}
        </div>
      ))}
      <form noValidate onSubmit={(event) => void submit(event)} className={styles.stack}>
        <FormField label={t('support.riskType')} required>
          <Select {...form.register('riskType')}>
            {Object.values(RiskType).map((type) => (
              <option key={type} value={type}>
                {t(`support.risks.${type}`)}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label={t('support.riskSummary')} required error={form.formState.errors.publicSummary?.message}>
          <Textarea rows={2} {...form.register('publicSummary', { required: t('support.errRisk'), minLength: { value: 5, message: t('support.errRisk') }, maxLength: { value: 500, message: t('support.errRisk') } })} />
        </FormField>
        {create.isError ? <InlineError error={create.error} /> : null}
        <div>
          <Button type="submit" variant="secondary" loading={create.isPending}>
            {t('support.riskSave')}
          </Button>
        </div>
      </form>
    </section>
  )
}
