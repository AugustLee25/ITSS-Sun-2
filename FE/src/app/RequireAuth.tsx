import { Navigate, Outlet, useLocation } from 'react-router'
import { InlineError, LoadingSkeleton } from '@/components'
import { useT } from '@/i18n'
import { useAuth } from './auth/authContext'
import { buildLoginUrl } from './redirect'
import { paths } from './routes'
import styles from './ErrorPages.module.css'

/**
 * Route bảo vệ (spec 8.3): chưa đăng nhập → S00, giữ `?invite=` và đích cũ (`?next=`).
 * Người dùng tự đăng xuất thì về S00 sạch (không `next`).
 */
export function RequireAuth() {
  const auth = useAuth()
  const location = useLocation()
  const t = useT()

  if (auth.status === 'loading') {
    return (
      <div className={styles.standalone}>
        <div className={styles.card}>
          <LoadingSkeleton variant="text" lines={3} label={t('auth.loadingSession')} />
        </div>
      </div>
    )
  }
  if (auth.status === 'error') {
    return (
      <div className={styles.standalone}>
        <div className={styles.card}>
          <InlineError error={auth.error} title={t('auth.sessionError')} onRetry={auth.reload} />
        </div>
      </div>
    )
  }
  if (auth.status === 'anonymous') {
    const to = auth.sessionEnd === 'signed-out' ? paths.login() : buildLoginUrl(location)
    return <Navigate to={to} replace />
  }
  return <Outlet />
}
