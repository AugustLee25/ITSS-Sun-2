import { Link } from 'react-router'
import { useMyGroups } from '@/api/groups'
import type { GroupCard } from '@/api/types'
import { GroupRole } from '@/api/types'
import { paths } from '@/app/routes'
import {
  ButtonLink,
  Card,
  CardChips,
  CardHead,
  EmptyState,
  InlineError,
  LoadingSkeleton,
  MetaList,
  PageHeader,
  StatusChip,
  StretchedLink,
} from '@/components'
import { useT } from '@/i18n'
import { formatCalendarDate } from '@/lib/zonedTime'
import styles from './Groups.module.css'

function GroupCardView({ group }: { group: GroupCard }) {
  const t = useT()
  const needsAttention = group.attention.count > 0
  return (
    <Card as="li" interactive accent={needsAttention ? 'info' : 'none'} className={styles.card}>
      <CardHead
        title={<StretchedLink to={paths.group(group.id)}>{group.name}</StretchedLink>}
        status={
          <StatusChip
            tone="neutral"
            icon={false}
            size="sm"
            label={group.myRole === GroupRole.LEAD ? t('roles.leader') : t('roles.member')}
          />
        }
      />
      {group.courseName ? <p className={styles.course}>{group.courseName}</p> : null}
      <MetaList
        items={[
          { label: t('groups.membersLabel'), value: group.memberCount },
          { label: t('groups.deadline'), value: <time dateTime={group.deadline}>{formatCalendarDate(group.deadline)}</time> },
        ]}
      />
      <CardChips>
        <StatusChip
          tone={needsAttention ? 'info' : 'success'}
          icon={needsAttention ? 'bell' : 'check'}
          label={group.attention.label || t('groups.attentionNone')}
        />
      </CardChips>
      {group.unreadNotifications > 0 ? (
        <p className={styles.unread}>
          <Link to={paths.notifications()}>{t('groups.unreadLink', { count: group.unreadNotifications })}</Link>
        </p>
      ) : null}
    </Card>
  )
}

/** S01 — Nhóm của tôi: chọn đúng nhóm trong một lần chạm; không có biểu đồ tiến độ trên thẻ (spec S01). */
export default function S01Groups() {
  const t = useT()
  const query = useMyGroups()
  const groups = query.data

  return (
    <>
      <PageHeader
        title={t('groups.title')}
        actions={
          <div className={styles.actions}>
            <ButtonLink to={paths.groupNew()} icon="plus">
              {t('groups.create')}
            </ButtonLink>
            <ButtonLink to={paths.groupJoin()} variant="secondary">
              {t('groups.joinByCode')}
            </ButtonLink>
          </div>
        }
      />

      {query.isPending ? (
        <LoadingSkeleton variant="list" count={3} label={t('states.loading')} />
      ) : query.isError ? (
        <InlineError error={query.error} title={t('groups.loadError')} onRetry={() => void query.refetch()} />
      ) : groups && groups.length === 0 ? (
        <EmptyState
          icon="users"
          title={t('groups.emptyTitle')}
          description={t('groups.emptyDescription')}
          action={
            <div className={styles.actions}>
              <ButtonLink to={paths.groupNew()} icon="plus">
                {t('groups.create')}
              </ButtonLink>
              <ButtonLink to={paths.groupJoin()} variant="secondary">
                {t('groups.joinByCode')}
              </ButtonLink>
            </div>
          }
        />
      ) : (
        <ul className={styles.grid} aria-label={t('groups.listLabel')}>
          {groups?.map((group) => <GroupCardView key={group.id} group={group} />)}
        </ul>
      )}
    </>
  )
}
