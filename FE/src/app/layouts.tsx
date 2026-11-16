import { Suspense, useMemo, type ReactNode } from 'react'
import { Outlet, ScrollRestoration, useNavigate, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { hasStatus, isClientRequestError } from '@/api/errors'
import { GroupRole } from '@/api/types'
import { AppShell, GroupSwitcher, InlineError, LoadingSkeleton, type NavItem } from '@/components'
import { useT } from '@/i18n'
import { DEFAULT_TIME_ZONE } from '@/lib/format'
import { TimeZoneContext } from '@/lib/timeZoneContext'
import { useAuth } from './auth/authContext'
import { ForbiddenState, NotFoundState } from './ErrorPages'
import { GroupContext, type GroupContextValue } from './group/groupContext'
import { useUnreadCount } from './useUnreadCount'
import { paths } from './routes'
import { fetchGroup, fetchMyGroups, queryKeys } from './shellApi'

function PageFallback() {
  return <LoadingSkeleton variant="list" count={3} />
}

/** Lớp gốc: khôi phục vị trí cuộn khi chuyển trang / quay lại. */
export function RootLayout() {
  return (
    <>
      <Outlet />
      <ScrollRestoration />
    </>
  )
}

function useShellUser() {
  const { user, signOut } = useAuth()
  const navigate = useNavigate()
  return {
    user: user ? { name: user.name, email: user.email } : undefined,
    unreadCount: useUnreadCount(),
    onSignOut: () => {
      signOut()
      navigate(paths.login(), { replace: true })
    },
  }
}

/** Khung chỉ có header cho các trang ngoài nhóm: S01, S02, S13 và trạng thái lỗi nhóm. */
export function UserLayout({ children }: { children?: ReactNode }) {
  const shell = useShellUser()
  return (
    <AppShell {...shell} homeHref={paths.groups()} notificationsHref={paths.notifications()}>
      <Suspense fallback={<PageFallback />}>{children ?? <Outlet />}</Suspense>
    </AppShell>
  )
}

/**
 * Khung của một nhóm (`/groups/:groupId/...`): kiểm tra thành viên (403/404 thân thiện), cung cấp
 * GroupContext + múi giờ nhóm, sidebar/bottom nav 4 mục và GroupSwitcher.
 */
export function GroupLayout() {
  const t = useT()
  const { groupId = '' } = useParams()
  const shell = useShellUser()

  const groupQuery = useQuery({
    queryKey: queryKeys.group(groupId),
    queryFn: ({ signal }) => fetchGroup(groupId, signal),
    enabled: !!groupId,
    retry: (count, error) => !isClientRequestError(error) && count < 1,
  })
  const myGroups = useQuery({ queryKey: queryKeys.myGroups, queryFn: ({ signal }) => fetchMyGroups(signal) })

  const group = groupQuery.data
  const nav = useMemo<NavItem[]>(
    () => [
      { key: 'overview', label: t('nav.overview'), to: paths.group(groupId), icon: 'overview', end: true },
      { key: 'tasks', label: t('nav.tasks'), to: paths.tasks(groupId), icon: 'tasks' },
      { key: 'help', label: t('nav.help'), to: paths.help(groupId), icon: 'help' },
      { key: 'points', label: t('nav.points'), to: paths.points(groupId), icon: 'award' },
    ],
    [groupId, t],
  )
  const contextValue = useMemo<GroupContextValue | null>(
    () => (group ? { groupId, group, role: group.myRole, isLeader: group.myRole === GroupRole.LEAD, timeZone: group.timezone } : null),
    [group, groupId],
  )

  if (groupQuery.isError) {
    const error = groupQuery.error
    return (
      <UserLayout>
        {hasStatus(error, 403) ? (
          <ForbiddenState />
        ) : hasStatus(error, 404) ? (
          <NotFoundState />
        ) : (
          <InlineError error={error} title={t('states.errorTitle')} onRetry={() => void groupQuery.refetch()} />
        )}
      </UserLayout>
    )
  }

  const switcher = (
    <GroupSwitcher
      groups={(myGroups.data ?? (group ? [group] : [])).map((g) => ({
        id: g.id,
        name: g.name,
        roleLabel: g.myRole === GroupRole.LEAD ? t('roles.leader') : t('roles.member'),
      }))}
      currentId={groupId}
      currentName={group?.name}
      hrefFor={paths.group}
      allGroupsHref={paths.groups()}
      createHref={paths.groupNew()}
    />
  )

  return (
    <TimeZoneContext.Provider value={contextValue?.timeZone ?? DEFAULT_TIME_ZONE}>
      <GroupContext.Provider value={contextValue}>
        <AppShell
          {...shell}
          nav={nav}
          groupSwitcher={switcher}
          homeHref={paths.groups()}
          notificationsHref={paths.notifications()}
          settingsHref={contextValue?.isLeader ? paths.groupSettings(groupId) : undefined}
        >
          {contextValue ? (
            <Suspense fallback={<PageFallback />}>
              <Outlet />
            </Suspense>
          ) : (
            <PageFallback />
          )}
        </AppShell>
      </GroupContext.Provider>
    </TimeZoneContext.Provider>
  )
}
