import { useId, type ReactNode } from 'react'
import { Link, NavLink } from 'react-router'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { Icon } from '../Icon/Icon'
import { MemberAvatar } from '../MemberAvatar/MemberAvatar'
import { usePopover } from '../Popover/usePopover'
import { BottomNav, type NavItem } from './BottomNav'
import styles from './AppShell.module.css'

export interface AppShellUser {
  name: string
  email?: string
  avatarUrl?: string | null
}

export interface AppShellProps {
  /** Các mục điều hướng chính của nhóm (4 mục). Bỏ trống ở S01/S02/S13 → chỉ có header. */
  nav?: NavItem[]
  /** Thường là `<GroupSwitcher />`. */
  groupSwitcher?: ReactNode
  /** Số thông báo chưa đọc (badge trên chuông). */
  unreadCount?: number
  notificationsHref?: string
  /** Nếu có, chuông là nút (mở drawer S13) thay vì link tới trang. */
  onNotificationsClick?: () => void
  user?: AppShellUser
  onSignOut?: () => void
  /** Chỉ truyền cho Leader (spec 1.2: cài đặt nhóm). */
  settingsHref?: string
  /** Link logo + "Đổi nhóm" (S01). */
  homeHref?: string
  /** Link tới danh sách nhóm trong menu tài khoản; mặc định = homeHref. */
  children: ReactNode
}

/**
 * Khung ứng dụng: sidebar >=1024px, sidebar thu gọn 768–1023px, bottom nav <768px (spec 1.2/2.1).
 * Landmark: header (banner), nav, main#main-content (focus được để chuyển trang).
 */
export function AppShell({
  nav,
  groupSwitcher,
  unreadCount = 0,
  notificationsHref = '/notifications',
  onNotificationsClick,
  user,
  onSignOut,
  settingsHref,
  homeHref = '/groups',
  children,
}: AppShellProps) {
  const t = useT()
  const hasNav = !!nav && nav.length > 0
  const badge = unreadCount > 99 ? '99+' : String(unreadCount)
  const bellLabel = unreadCount > 0 ? t('a11y.notificationsWithUnread', { count: unreadCount }) : t('a11y.notifications')
  const bellContent = (
    <>
      <Icon name="bell" size={22} />
      {unreadCount > 0 ? (
        <span className={styles.badge} aria-hidden="true">
          {badge}
        </span>
      ) : null}
    </>
  )

  return (
    <div className={cx(styles.shell, hasNav && styles.withNav)}>
      <a href="#main-content" className="skip-link">
        {t('nav.skipToContent')}
      </a>

      {hasNav ? (
        <aside className={styles.sidebar}>
          <Link to={homeHref} className={styles.logo} aria-label={`${t('app.name')} — ${t('nav.groups')}`}>
            <span className={styles.logoMark} aria-hidden="true">
              H
            </span>
            <span className={styles.logoText}>{t('app.name')}</span>
          </Link>
          <nav aria-label={t('nav.primary')} className={styles.sideNav}>
            <ul>
              {nav.map((item) => (
                <li key={item.key}>
                  <NavLink to={item.to} end={item.end} className={styles.sideLink}>
                    <Icon name={item.icon} size={22} />
                    <span>{item.label}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
          <Link to={homeHref} className={cx(styles.sideLink, styles.switch)}>
            <Icon name="users" size={22} />
            <span>{t('nav.switchGroup')}</span>
          </Link>
        </aside>
      ) : null}

      <div className={styles.column}>
        <header className={styles.header}>
          {!hasNav ? (
            <Link to={homeHref} className={styles.logo} aria-label={`${t('app.name')} — ${t('nav.groups')}`}>
              <span className={styles.logoMark} aria-hidden="true">
                H
              </span>
              <span className={styles.logoText}>{t('app.name')}</span>
            </Link>
          ) : (
            <Link to={homeHref} className={cx(styles.logo, styles.headerLogo)} aria-label={t('app.name')}>
              <span className={styles.logoMark} aria-hidden="true">
                H
              </span>
            </Link>
          )}
          <div className={styles.headerMain}>{groupSwitcher}</div>
          <div className={styles.headerActions}>
            {onNotificationsClick ? (
              <button type="button" className={styles.iconButton} aria-label={bellLabel} onClick={onNotificationsClick}>
                {bellContent}
              </button>
            ) : (
              <Link to={notificationsHref} className={styles.iconButton} aria-label={bellLabel}>
                {bellContent}
              </Link>
            )}
            {user ? <AccountMenu user={user} onSignOut={onSignOut} settingsHref={settingsHref} homeHref={homeHref} /> : null}
          </div>
        </header>
        <main id="main-content" tabIndex={-1} className={styles.main}>
          <div className={styles.content}>{children}</div>
        </main>
      </div>

      {hasNav ? <BottomNav items={nav} /> : null}
    </div>
  )
}

function AccountMenu({
  user,
  onSignOut,
  settingsHref,
  homeHref,
}: {
  user: AppShellUser
  onSignOut?: () => void
  settingsHref?: string
  homeHref: string
}) {
  const t = useT()
  const panelId = useId()
  const { open, setOpen, close, triggerRef, panelRef } = usePopover()
  return (
    <div className={styles.account}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.iconButton}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={`${t('a11y.accountMenu')}: ${user.name}`}
        onClick={() => setOpen((v) => !v)}
      >
        <MemberAvatar name={user.name} src={user.avatarUrl} size="sm" decorative />
      </button>
      {open ? (
        <div id={panelId} ref={panelRef} className={styles.accountPanel}>
          <div className={styles.accountInfo}>
            <strong>{user.name}</strong>
            {user.email ? <span>{user.email}</span> : null}
          </div>
          <Link to={homeHref} className={styles.menuItem} onClick={() => close()}>
            <Icon name="users" size={18} />
            {t('nav.groups')}
          </Link>
          {settingsHref ? (
            <Link to={settingsHref} className={styles.menuItem} onClick={() => close()}>
              <Icon name="settings" size={18} />
              {t('nav.groupSettings')}
            </Link>
          ) : null}
          {onSignOut ? (
            <button
              type="button"
              className={styles.menuItem}
              onClick={() => {
                close()
                onSignOut()
              }}
            >
              <Icon name="log-out" size={18} />
              {t('nav.signOut')}
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
