import { useParams, useRouteError } from 'react-router'
import type { ReactNode } from 'react'
import { Button, ButtonLink, EmptyState, InlineError } from '@/components'
import { useT } from '@/i18n'
import { paths } from './routes'
import styles from './ErrorPages.module.css'

/** Spec 2.4 #6: giải thích quyền của người xem + đường về nhóm. */
export function ForbiddenState() {
  const t = useT()
  return (
    <EmptyState
      icon="lock"
      title={t('states.forbiddenTitle')}
      description={t('states.forbiddenDescription')}
      action={<ButtonLink to={paths.groups()}>{t('nav.goGroups')}</ButtonLink>}
    />
  )
}

/** Spec 2.4 #7: thông báo thân thiện + liên kết về danh sách. */
export function NotFoundState({ homeHref, homeLabel }: { homeHref?: string; homeLabel?: string }) {
  const t = useT()
  return (
    <EmptyState
      icon="search-x"
      title={t('states.notFoundTitle')}
      description={t('states.notFoundDescription')}
      action={<ButtonLink to={homeHref ?? paths.groups()}>{homeLabel ?? t('nav.goGroups')}</ButtonLink>}
    />
  )
}

/** 404 bên trong một nhóm: giữ khung nhóm, nút về trang chủ nhóm. */
export function GroupNotFound() {
  const t = useT()
  const { groupId } = useParams()
  return <NotFoundState homeHref={groupId ? paths.group(groupId) : paths.groups()} homeLabel={t('nav.goHome')} />
}

function Standalone({ children }: { children: ReactNode }) {
  return (
    <main id="main-content" tabIndex={-1} className={styles.standalone}>
      <div className={styles.card}>{children}</div>
    </main>
  )
}

/** Trang 404 cho mọi URL không khớp route. */
export function NotFoundPage() {
  return (
    <Standalone>
      <NotFoundState />
    </Standalone>
  )
}

/** `errorElement` gốc: lỗi render hoặc lỗi tải chunk trang. */
export function RouteErrorPage() {
  const t = useT()
  const error = useRouteError()
  return (
    <Standalone>
      <InlineError title={t('states.errorTitle')} message={t('states.errorDescription')} error={error} />
      <div className={styles.actions}>
        <Button onClick={() => window.location.reload()} icon="refresh">
          {t('common.reload')}
        </Button>
        <ButtonLink variant="secondary" to={paths.groups()}>
          {t('nav.goGroups')}
        </ButtonLink>
      </div>
    </Standalone>
  )
}
