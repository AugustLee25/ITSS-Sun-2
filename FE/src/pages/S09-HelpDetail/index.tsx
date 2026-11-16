import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useAcceptOffer, useCancelHelpRequest, useCreateOffer, useHelpRequest, useReconfirmOffer, useWithdrawOffer } from '@/api/help'
import { hasStatus, isApiError } from '@/api/errors'
import { HelpRequestAction, SupportOfferStatus, type HelpRequestDetail, type SupportOffer } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import {
  Button,
  ButtonLink,
  Card,
  ConfirmDialog,
  EmptyState,
  FormField,
  InlineError,
  LoadingSkeleton,
  MetaList,
  Modal,
  PageHeader,
  PointsText,
  StatusChip,
  Textarea,
  useStatusChip,
  useToast,
} from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import styles from './HelpDetail.module.css'

/** S09 — Chi tiết yêu cầu hỗ trợ: phạm vi/hạn/điểm do server trả; đề nghị tự nguyện, bỏ qua không để lại dấu vết. */
export default function S09HelpDetail() {
  const t = useT()
  const { groupId } = useGroup()
  const { helpRequestId } = useParams()
  const query = useHelpRequest(helpRequestId)
  const data = query.data

  if (query.isLoading) return <LoadingSkeleton variant="list" count={2} />
  if (query.isError || !data) {
    const missing = hasStatus(query.error, 403, 404)
    return missing ? (
      <EmptyState
        icon="help"
        title={t('states.notFoundTitle')}
        description={t('helpDetail.notFound')}
        action={<ButtonLink to={paths.help(groupId)}>{t('helpDetail.backToBoard')}</ButtonLink>}
      />
    ) : (
      <InlineError error={query.error} title={t('helpDetail.loadFailed')} onRetry={() => void query.refetch()} />
    )
  }
  return <Detail data={data} groupId={groupId} onReload={() => void query.refetch()} />
}

function Detail({ data, groupId, onReload }: { data: HelpRequestDetail; groupId: string; onReload: () => void }) {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const can = (action: HelpRequestAction) => data.allowedActions.includes(action)
  const impact = data.myImpact

  return (
    <>
      <PageHeader
        title={data.title}
        breadcrumbs={[{ label: t('nav.help'), to: paths.help(groupId) }, { label: data.title }]}
        status={<StatusChip tone={chip('help', data.status).tone} label={data.statusLabel} />}
      />
      <div className={styles.layout}>
        <Card as="section" aria-label={t('helpDetail.infoLabel')}>
          <MetaList
            items={[
              { label: t('fields.task'), value: <Link to={paths.task(groupId, data.task.id)}>{data.task.title}</Link> },
              { label: t('fields.requester'), value: data.postedBy.name },
              { label: t('helpDetail.beneficiary'), value: data.beneficiary.name },
              { label: t('helpDetail.approver'), value: data.resultApprover.name },
              { label: t('helpDetail.output'), value: data.expectedOutput },
              { label: t('fields.deadline'), value: <time dateTime={data.deadline}>{f.dateTime(data.deadline)}</time> },
              { label: t('fields.difficulty'), value: t(`status.difficulty.${data.difficulty}`) },
              { label: t('fields.effort'), value: data.estimatedEffortMinutes ? t('helpBoard.effortMinutes', { minutes: data.estimatedEffortMinutes }) : null },
              {
                label: t('fields.points'),
                value: data.points ? <PointsText>{t('helpBoard.pointsAfterApproval', { points: f.pointsLabel(data.points, { signed: true }) })}</PointsText> : t('helpDetail.noPoints'),
              },
            ]}
          />
          <h2 className={styles.h2}>{t('helpDetail.scope')}</h2>
          <p className={styles.pre}>{data.description}</p>
          {data.skillNotes ? (
            <>
              <h2 className={styles.h2}>{t('helpDetail.skills')}</h2>
              <p className={styles.pre}>{data.skillNotes}</p>
            </>
          ) : null}
          {data.pointExplanation ? <p className={styles.note}>{data.pointExplanation}</p> : null}
          {impact ? (
            <p className={styles.note}>
              {t('helpDetail.impact', { before: impact.potentialBefore.percentLabel, after: impact.potentialAfter.percentLabel })}{' '}
              <Link to={paths.points(groupId)}>{t('helpDetail.impactLink')}</Link>
            </p>
          ) : null}
        </Card>

        {can(HelpRequestAction.OFFER_HELP) ? <OfferBlock data={data} groupId={groupId} onReload={onReload} /> : null}
        {data.myOffer && data.myOffer.status !== SupportOfferStatus.WITHDRAWN ? <MyOfferBlock data={data} offer={data.myOffer} groupId={groupId} /> : null}
        {can(HelpRequestAction.ACCEPT_OFFER) || data.offers.length > 0 ? <OffersBlock data={data} groupId={groupId} onReload={onReload} /> : null}
        {can(HelpRequestAction.OPEN_WORK) ? (
          <div>
            <ButtonLink to={paths.helpWork(groupId, data.id)} icon="arrow-right">
              {t('helpDetail.openWork')}
            </ButtonLink>
          </div>
        ) : null}
        {can(HelpRequestAction.CANCEL) ? <CancelBlock data={data} groupId={groupId} /> : null}
      </div>
    </>
  )
}

/** Thành viên khác: `Mình có thể hỗ trợ` → dialog tóm tắt → `Gửi đề nghị`. Có `Để sau` không lưu gì. */
function OfferBlock({ data, groupId, onReload }: { data: HelpRequestDetail; groupId: string; onReload: () => void }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  return (
    <Card as="section" aria-labelledby="offer-heading" accent="info">
      <h2 id="offer-heading" className={styles.h2}>
        {t('helpDetail.canHelp')}
      </h2>
      <p>{t('helpDetail.reassure')}</p>
      <div className={styles.actions}>
        <Button size="lg" onClick={() => setOpen(true)}>
          {t('copy.offerHelp')}
        </Button>
        <ButtonLink to={paths.help(groupId)} variant="ghost">
          {t('helpDetail.later')}
        </ButtonLink>
      </div>
      {open ? <OfferDialog data={data} groupId={groupId} onClose={() => setOpen(false)} onReload={onReload} /> : null}
    </Card>
  )
}

function OfferDialog({ data, groupId, onClose, onReload }: { data: HelpRequestDetail; groupId: string; onClose: () => void; onReload: () => void }) {
  const t = useT()
  const f = useFormatters()
  const toast = useToast()
  const create = useCreateOffer(groupId, data.id)
  const [message, setMessage] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [showAckError, setShowAckError] = useState(false)
  const matched = isApiError(create.error) && create.error.code === 'HELP_ALREADY_MATCHED'

  function send() {
    if (create.isPending) return
    if (!acknowledged) return setShowAckError(true)
    create.mutate(
      { message: message.trim() || null, acknowledgedScope: true, helpRequestVersion: data.version },
      {
        onSuccess: () => {
          toast.success(t('helpDetail.offerSent'))
          onClose()
        },
        onError: (error) => {
          if (hasStatus(error, 409)) onReload()
        },
      },
    )
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={t('helpDetail.dialogTitle')}
      description={t('helpDetail.dialogDescription')}
      footer={
        matched ? (
          <ButtonLink to={paths.help(groupId)}>{t('helpDetail.backToBoard')}</ButtonLink>
        ) : (
          <>
            <Button variant="secondary" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button loading={create.isPending} onClick={send}>
              {t('helpDetail.sendOffer')}
            </Button>
          </>
        )
      }
    >
      <div className={styles.stack}>
        <MetaList
          items={[
            { label: t('fields.task'), value: data.title },
            { label: t('helpDetail.output'), value: data.expectedOutput },
            { label: t('fields.deadline'), value: f.dateTime(data.deadline) },
            { label: t('fields.points'), value: data.points ? t('helpBoard.pointsAfterApproval', { points: f.pointsLabel(data.points, { signed: true }) }) : null },
            { label: t('helpDetail.preview'), value: data.myImpact ? t('helpDetail.impactShort', { before: data.myImpact.potentialBefore.percentLabel, after: data.myImpact.potentialAfter.percentLabel }) : null },
          ]}
        />
        <p className={styles.note}>{t('helpDetail.condition')}</p>
        <FormField label={t('helpDetail.message')} hint={t('common.optional')}>
          <Textarea rows={2} maxLength={500} value={message} onChange={(event) => setMessage(event.target.value)} />
        </FormField>
        <label className={styles.check}>
          <input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} />
          <span>{t('helpDetail.acknowledge')}</span>
        </label>
        {showAckError && !acknowledged ? <p role="alert" className={styles.error}>{t('helpDetail.errAcknowledge')}</p> : null}
        {create.isError ? <InlineError error={create.error} /> : null}
      </div>
    </Modal>
  )
}

/** Đề nghị của tôi: chờ phản hồi (rút / xác nhận lại), được chọn, hoặc chưa được chọn lần này. */
function MyOfferBlock({ data, offer, groupId }: { data: HelpRequestDetail; offer: SupportOffer; groupId: string }) {
  const t = useT()
  const toast = useToast()
  const withdraw = useWithdrawOffer(groupId)
  const reconfirm = useReconfirmOffer(groupId)
  const error = withdraw.error ?? reconfirm.error
  return (
    <Card as="section" aria-labelledby="my-offer-heading" accent={offer.status === SupportOfferStatus.ACCEPTED ? 'success' : 'pending'}>
      <h2 id="my-offer-heading" className={styles.h2}>
        {t('helpDetail.myOffer')}
      </h2>
      {offer.status === SupportOfferStatus.PENDING ? <p>{offer.needsReconfirmation ? t('helpDetail.needsReconfirm') : t('helpDetail.offerPending')}</p> : null}
      {offer.status === SupportOfferStatus.ACCEPTED ? <p>{t('helpDetail.offerAccepted')}</p> : null}
      {offer.status === SupportOfferStatus.NOT_SELECTED ? <p>{t('helpDetail.offerNotSelected')}</p> : null}
      {offer.status === SupportOfferStatus.PENDING ? (
        <div className={styles.actions}>
          {offer.needsReconfirmation ? (
            <Button
              loading={reconfirm.isPending}
              onClick={() => reconfirm.mutate({ offerId: offer.id, helpRequestVersion: data.version }, { onSuccess: () => toast.success(t('helpDetail.reconfirmed')) })}
            >
              {t('helpDetail.reconfirm')}
            </Button>
          ) : null}
          <Button variant="secondary" loading={withdraw.isPending} onClick={() => withdraw.mutate(offer.id, { onSuccess: () => toast.success(t('helpDetail.withdrawn')) })}>
            {t('helpDetail.withdraw')}
          </Button>
        </div>
      ) : null}
      {error ? <InlineError error={error} /> : null}
    </Card>
  )
}

/** TL (người đăng): xem những người đã đề nghị, chọn một Helper. `Không chọn` không gọi API — chỉ ẩn khỏi danh sách đang xem. */
function OffersBlock({ data, groupId, onReload }: { data: HelpRequestDetail; groupId: string; onReload: () => void }) {
  const t = useT()
  const f = useFormatters()
  const toast = useToast()
  const navigate = useNavigate()
  const accept = useAcceptOffer(groupId, data.id)
  const [target, setTarget] = useState<SupportOffer | null>(null)
  const [hidden, setHidden] = useState<string[]>([])
  const [accepted, setAccepted] = useState(false)
  const canAccept = data.allowedActions.includes(HelpRequestAction.ACCEPT_OFFER)
  const offers = data.offers.filter((offer) => !hidden.includes(offer.id))

  useEffect(() => {
    if (accepted) navigate(paths.helpWork(groupId, data.id))
  }, [accepted, groupId, data.id, navigate])

  return (
    <Card as="section" aria-labelledby="offers-heading">
      <h2 id="offers-heading" className={styles.h2}>
        {t('helpDetail.offersTitle')}
      </h2>
      {offers.length === 0 ? <p className={styles.note}>{t('helpDetail.noOffers')}</p> : null}
      <ul className={styles.offers}>
        {offers.map((offer) => (
          <li key={offer.id} className={styles.offer}>
            <div>
              <strong>{offer.helper.name}</strong> <span className={styles.note}>{f.relative(offer.createdAt)}</span>
              {offer.needsReconfirmation ? <StatusChip tone="warning" size="sm" label={t('helpDetail.awaitingReconfirm')} /> : null}
              {offer.message ? <p className={styles.pre}>{offer.message}</p> : null}
            </div>
            {canAccept ? (
              <div className={styles.actions}>
                <Button disabled={offer.needsReconfirmation} onClick={() => setTarget(offer)}>
                  {t('helpDetail.accept')}
                </Button>
                <Button variant="ghost" onClick={() => setHidden((ids) => [...ids, offer.id])}>
                  {t('helpDetail.skip')}
                </Button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      {accept.isError && !target ? <InlineError error={accept.error} /> : null}
      <ConfirmDialog
        open={!!target}
        title={t('helpDetail.acceptTitle', { name: target?.helper.name ?? '' })}
        description={t('helpDetail.acceptDescription', { deadline: f.dateTime(data.deadline) })}
        confirmLabel={t('helpDetail.accept')}
        loading={accept.isPending}
        onCancel={() => setTarget(null)}
        onConfirm={() => {
          if (!target) return
          accept.mutate(
            { offerId: target.id, version: data.version },
            {
              onSuccess: () => {
                toast.success(t('helpDetail.accepted'))
                setTarget(null)
                setAccepted(true)
              },
              onError: (error) => {
                setTarget(null)
                if (hasStatus(error, 409)) onReload()
              },
            },
          )
        }}
      />
    </Card>
  )
}

function CancelBlock({ data, groupId }: { data: HelpRequestDetail; groupId: string }) {
  const t = useT()
  const toast = useToast()
  const cancel = useCancelHelpRequest(groupId, data.id)
  const [open, setOpen] = useState(false)
  return (
    <div>
      <Button variant="ghost" onClick={() => setOpen(true)}>
        {t('helpDetail.cancel')}
      </Button>
      {cancel.isError ? <InlineError error={cancel.error} /> : null}
      <ConfirmDialog
        open={open}
        tone="danger"
        title={t('helpDetail.cancelTitle')}
        description={t('helpDetail.cancelDescription')}
        confirmLabel={t('helpDetail.cancel')}
        loading={cancel.isPending}
        onCancel={() => setOpen(false)}
        onConfirm={() =>
          cancel.mutate(
            { version: data.version },
            {
              onSuccess: () => {
                setOpen(false)
                toast.success(t('helpDetail.cancelled'))
              },
              onError: () => setOpen(false),
            },
          )
        }
      />
    </div>
  )
}
