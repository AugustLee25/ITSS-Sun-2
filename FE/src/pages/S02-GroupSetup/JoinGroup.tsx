import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useInvitePreview } from '@/api/auth'
import { useAcceptInvite } from '@/api/groups'
import { isApiError } from '@/api/errors'
import { ErrorCode, type InvitePreview } from '@/api/types'
import { paths } from '@/app/routes'
import { Button, ButtonLink, Card, FormField, InlineError, Input, LoadingSkeleton, MetaList, useToast } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { formatDateTime } from '@/lib/format'
import { DEFAULT_TIME_ZONE } from '@/lib/format'
import { extractInviteCode } from './scheduleForm'
import styles from './GroupSetup.module.css'

const KNOWN_INVITE_ERRORS: string[] = [
  ErrorCode.INVITE_NOT_FOUND,
  ErrorCode.INVITE_EXPIRED,
  ErrorCode.INVITE_REVOKED,
  ErrorCode.ALREADY_MEMBER,
]

/** Lý do + cách xử lý khi mã/link mời không dùng được (spec S02 chế độ tham gia). */
function InviteProblem({ error, preview }: { error: unknown; preview?: InvitePreview }) {
  const t = useT()
  if (isApiError(error) && KNOWN_INVITE_ERRORS.includes(error.code)) {
    return (
      <div role="alert" className={styles.problem}>
        <p className={styles.problemTitle}>{t(`groupSetup.inviteErrors.${error.code}.title` as MessageKey)}</p>
        <p>{t(`groupSetup.inviteErrors.${error.code}.hint` as MessageKey)}</p>
        {error.code === ErrorCode.ALREADY_MEMBER && preview?.groupId ? (
          <ButtonLink to={paths.group(preview.groupId)}>{t('groupSetup.goToGroup')}</ButtonLink>
        ) : null}
        {error.code === ErrorCode.ALREADY_MEMBER && !preview?.groupId ? (
          <ButtonLink to={paths.groups()}>{t('nav.goGroups')}</ButtonLink>
        ) : null}
      </div>
    )
  }
  return <InlineError error={error} title={t('groupSetup.joinTitle')} />
}

/** S02 (tham gia): nhập mã/link → xem trước nhóm → tham gia. Mở bằng `/join/:code` thì kiểm tra sẵn. */
export function JoinGroup({ initialCode }: { initialCode?: string }) {
  const t = useT()
  const navigate = useNavigate()
  const toast = useToast()
  const [input, setInput] = useState(initialCode ?? '')
  const [checkedCode, setCheckedCode] = useState<string | null>(initialCode ? extractInviteCode(initialCode) || null : null)
  const [emptyError, setEmptyError] = useState(false)

  const preview = useInvitePreview(checkedCode)
  const accept = useAcceptInvite()

  function onCheck(event: FormEvent) {
    event.preventDefault()
    const code = extractInviteCode(input)
    if (!code) {
      setEmptyError(true)
      setCheckedCode(null)
      return
    }
    setEmptyError(false)
    accept.reset()
    setCheckedCode(code)
  }

  function onJoin() {
    if (!checkedCode || accept.isPending) return
    accept.mutate(checkedCode, {
      onSuccess: ({ group }) => {
        toast.success(t('groupSetup.joined', { name: group.name }))
        navigate(paths.group(group.id), { replace: true })
      },
    })
  }

  const data = preview.data

  return (
    <div className={styles.card}>
      <h2 className={styles.stepTitle}>{t('groupSetup.joinTitle')}</h2>
      <form noValidate onSubmit={onCheck} className={styles.joinForm}>
        <FormField
          label={t('groupSetup.joinField')}
          required
          hint={t('groupSetup.joinHint')}
          error={emptyError ? t('groupSetup.joinEmpty') : undefined}
        >
          <Input
            value={input}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            placeholder={t('groupSetup.joinPlaceholder')}
            onChange={(event) => {
              setInput(event.target.value)
              setEmptyError(false)
            }}
          />
        </FormField>
        <Button type="submit" variant="secondary" loading={!!checkedCode && preview.isFetching}>
          {preview.isFetching ? t('groupSetup.joinChecking') : t('groupSetup.joinCheck')}
        </Button>
      </form>

      {checkedCode && preview.isPending ? <LoadingSkeleton variant="card" label={t('groupSetup.joinChecking')} /> : null}
      {preview.isError ? <InviteProblem error={preview.error} /> : null}

      {data ? (
        <Card as="section" aria-label={data.groupName} accent={data.alreadyMember ? 'success' : 'info'} className={styles.invitePreview}>
          <h3>{data.groupName}</h3>
          <MetaList
            items={[
              data.courseName ? { label: t('groupSetup.inviteCourse'), value: data.courseName } : null,
              { label: t('groupSetup.inviteLeader'), value: data.leader.name },
              { label: t('groupSetup.inviteMembers'), value: data.memberCount },
              data.expiresAt ? { label: t('groupSetup.inviteValidUntil'), value: formatDateTime(data.expiresAt, DEFAULT_TIME_ZONE) } : null,
            ]}
          />
          {data.alreadyMember ? (
            <div className={styles.problem}>
              <p className={styles.problemTitle}>{t('groupSetup.alreadyMemberTitle')}</p>
              <p>{t('groupSetup.alreadyMemberDescription')}</p>
              <ButtonLink to={data.groupId ? paths.group(data.groupId) : paths.groups()}>{t('groupSetup.goToGroup')}</ButtonLink>
            </div>
          ) : (
            <Button onClick={onJoin} loading={accept.isPending}>
              {accept.isPending ? t('groupSetup.joining') : t('groupSetup.joinConfirm')}
            </Button>
          )}
          {accept.isError ? <InviteProblem error={accept.error} preview={data} /> : null}
        </Card>
      ) : null}
    </div>
  )
}
