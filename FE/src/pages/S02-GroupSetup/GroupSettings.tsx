import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { hasStatus } from '@/api/errors'
import { useCreateInvite, useGroupInvite, useRevokeInvite } from '@/api/groups'
import { useSaveSchedule, useSchedulePreview, useSchedules } from '@/api/schedule'
import type { CheckpointSchedule } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import {
  Button,
  ButtonLink,
  Card,
  ConfirmDialog,
  EmptyState,
  InlineError,
  LoadingSkeleton,
  PageHeader,
  UnsavedChangesGuard,
  useToast,
} from '@/components'
import { useT } from '@/i18n'
import { formatDateTime } from '@/lib/format'
import { InviteShare } from './InviteShare'
import { ScheduleFields, SchedulePreviewPanel } from './ScheduleFields'
import { scheduleValuesFrom, toPreviewRequest, toScheduleInput, type ScheduleValues } from './scheduleForm'
import styles from './GroupSetup.module.css'

function ScheduleEditor({ current }: { current: CheckpointSchedule }) {
  const t = useT()
  const toast = useToast()
  const { groupId } = useGroup()
  const save = useSaveSchedule(groupId)
  const initial = scheduleValuesFrom(current)
  const form = useForm<ScheduleValues>({ defaultValues: initial })
  const watched = useWatch({ control: form.control }) as Partial<ScheduleValues>
  const values: ScheduleValues = { ...initial, ...watched }
  const unchanged =
    values.weekday === initial.weekday &&
    values.cutoffTime === initial.cutoffTime &&
    values.timezone === initial.timezone &&
    values.window === initial.window
  const preview = useSchedulePreview(unchanged ? null : toPreviewRequest(values, { groupId }))
  const [agreedError, setAgreedError] = useState(false)

  function setField(name: keyof ScheduleValues, value: string | boolean) {
    form.setValue(name, value as never, { shouldDirty: true })
    if (name === 'agreed') setAgreedError(false)
  }

  function onSave() {
    if (save.isPending) return
    if (!values.agreed) {
      setAgreedError(true)
      return
    }
    save.mutate(
      { ...toScheduleInput(values), scheduleAgreed: true },
      {
        onSuccess: () => {
          toast.success(t('groupSetup.scheduleSaved'))
          form.reset({ ...values, agreed: false })
        },
      },
    )
  }

  return (
    <>
      <UnsavedChangesGuard when={form.formState.isDirty && !save.isSuccess} />
      <p className={styles.muted}>{t('groupSetup.changeScheduleHint')}</p>
      <ScheduleFields values={values} onChange={(patch) => Object.entries(patch).forEach(([k, v]) => setField(k as keyof ScheduleValues, v as string | boolean))} />
      {unchanged ? (
        <p className={styles.muted}>{t('groupSetup.scheduleUnchanged')}</p>
      ) : (
        <SchedulePreviewPanel query={preview} timeZone={values.timezone} />
      )}
      <div className={styles.agreeField}>
        <label className={styles.checkbox}>
          <input
            type="checkbox"
            checked={values.agreed}
            aria-invalid={agreedError ? true : undefined}
            aria-describedby={agreedError ? 'agreed-error' : undefined}
            onChange={(event) => setField('agreed', event.target.checked)}
          />
          <span>
            <strong>{t('groupSetup.agreed')}</strong>
            <span className={styles.muted}> {t('groupSetup.agreedHint')}</span>
          </span>
        </label>
        {agreedError ? (
          <p id="agreed-error" role="alert" className={styles.fieldError}>
            {t('groupSetup.errAgreed')}
          </p>
        ) : null}
      </div>
      {save.isError ? <InlineError error={save.error} title={t('groupSetup.scheduleSaveFailed')} /> : null}
      <div className={styles.actions}>
        <Button onClick={onSave} loading={save.isPending} disabled={unchanged}>
          {save.isPending ? t('groupSetup.savingSchedule') : t('groupSetup.saveSchedule')}
        </Button>
      </div>
    </>
  )
}

function InviteSection() {
  const t = useT()
  const toast = useToast()
  const { groupId, group } = useGroup()
  const invite = useGroupInvite(groupId)
  const create = useCreateInvite(groupId)
  const revoke = useRevokeInvite(groupId)
  const [confirmRevoke, setConfirmRevoke] = useState(false)
  const noInvite = hasStatus(invite.error, 404)

  return (
    <>
      {invite.isPending ? <LoadingSkeleton variant="text" lines={2} /> : null}
      {invite.isError && !noInvite ? <InlineError error={invite.error} onRetry={() => void invite.refetch()} /> : null}
      {noInvite ? <p className={styles.muted}>{t('groupSetup.inviteNone')}</p> : null}
      {invite.data ? (
        <InviteShare code={invite.data.code} sharePath={invite.data.sharePath} expiresAt={invite.data.expiresAt} timeZone={group.timezone} />
      ) : null}
      {create.isError || revoke.isError ? (
        <InlineError error={create.error ?? revoke.error} title={t('groupSetup.inviteFailed')} />
      ) : null}
      <div className={styles.actions}>
        <Button
          variant="secondary"
          loading={create.isPending}
          onClick={() => create.mutate(undefined, { onSuccess: () => toast.success(t('groupSetup.inviteCreated')) })}
        >
          {invite.data ? t('groupSetup.inviteRegenerate') : t('groupSetup.inviteCreate')}
        </Button>
        {invite.data ? (
          <Button variant="ghost" onClick={() => setConfirmRevoke(true)}>
            {t('groupSetup.inviteRevoke')}
          </Button>
        ) : null}
      </div>
      {invite.data ? <p className={styles.muted}>{t('groupSetup.inviteRegenerateHint')}</p> : null}
      <ConfirmDialog
        open={confirmRevoke}
        tone="danger"
        title={t('groupSetup.inviteRevokeConfirm')}
        description={t('groupSetup.inviteRevokeDescription')}
        confirmLabel={t('groupSetup.inviteRevoke')}
        loading={revoke.isPending}
        onCancel={() => setConfirmRevoke(false)}
        onConfirm={() =>
          revoke.mutate(undefined, {
            onSuccess: () => {
              setConfirmRevoke(false)
              toast.success(t('groupSetup.inviteRevoked'))
            },
            onError: () => setConfirmRevoke(false),
          })
        }
      />
    </>
  )
}

/**
 * Cài đặt nhóm cho TL (`/groups/:groupId/settings`, spec 1.2 "cài đặt nhóm (Leader)"): đổi lịch checkpoint
 * (spec 0.5: lịch mới chỉ áp dụng cho kỳ chưa mở) và quản lý mã mời. Quyết định giữ route: docs/SPEC_ISSUES.md.
 */
export function GroupSettings() {
  const t = useT()
  const { groupId, group, isLeader } = useGroup()
  const schedules = useSchedules(groupId, isLeader)
  const current = schedules.data?.find((schedule) => schedule.isCurrent) ?? schedules.data?.[0]
  const history = schedules.data?.filter((schedule) => schedule.id !== current?.id) ?? []

  const header = (
    <PageHeader
      title={t('groupSetup.settingsTitle')}
      description={group.name}
      breadcrumbs={[{ label: group.name, to: paths.group(groupId) }, { label: t('groupSetup.settingsTitle') }]}
    />
  )

  if (!isLeader) {
    return (
      <>
        {header}
        <EmptyState
          icon="lock"
          title={t('groupSetup.settingsLeaderOnly')}
          description={t('groupSetup.settingsLeaderOnlyHint')}
          action={<ButtonLink to={paths.group(groupId)}>{t('groupSetup.settingsBack')}</ButtonLink>}
        />
      </>
    )
  }

  return (
    <>
      {header}
      <div className={styles.settings}>
        <Card as="section" aria-labelledby="settings-schedule">
          <h2 id="settings-schedule" className={styles.sectionTitle}>
            {t('groupSetup.currentSchedule')}
          </h2>
          {schedules.isPending ? <LoadingSkeleton variant="text" lines={2} /> : null}
          {schedules.isError ? <InlineError error={schedules.error} onRetry={() => void schedules.refetch()} /> : null}
          {current ? (
            <>
              <p className={styles.currentSchedule}>{current.summaryLabel}</p>
              <p className={styles.muted}>
                {t('groupSetup.scheduleSavedBy', {
                  name: current.recordedBy.name,
                  time: formatDateTime(current.agreedAt, group.timezone),
                })}
              </p>
              {history.length > 0 ? (
                <details className={styles.history}>
                  <summary>{t('groupSetup.scheduleHistory')}</summary>
                  <ul>
                    {history.map((schedule) => (
                      <li key={schedule.id}>
                        <strong>{t('groupSetup.scheduleVersion', { no: schedule.versionNo })}</strong> — {schedule.summaryLabel}
                        <span className={styles.muted}> ({formatDateTime(schedule.agreedAt, group.timezone)})</span>
                      </li>
                    ))}
                  </ul>
                </details>
              ) : null}
              <h3 className={styles.subTitle}>{t('groupSetup.changeSchedule')}</h3>
              <ScheduleEditor key={current.id} current={current} />
            </>
          ) : null}
        </Card>

        <Card as="section" aria-labelledby="settings-invite">
          <h2 id="settings-invite" className={styles.sectionTitle}>
            {t('groupSetup.inviteSection')}
          </h2>
          <InviteSection />
        </Card>
      </div>
    </>
  )
}
