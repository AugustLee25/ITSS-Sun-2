import { NavLink } from 'react-router'
import { useT } from '@/i18n'
import { Icon, type IconName } from '../Icon/Icon'
import styles from './AppShell.module.css'

export interface NavItem {
  key: string
  label: string
  to: string
  icon: IconName
  /** Chỉ active khi khớp chính xác (dùng cho mục "Tổng quan" ở gốc nhóm). */
  end?: boolean
}

/** Điều hướng đáy 4 mục cho mobile (<768px). Hiển thị/ẩn bằng CSS; chạm >= 44px, kèm nhãn chữ. */
export function BottomNav({ items }: { items: NavItem[] }) {
  const t = useT()
  return (
    <nav aria-label={t('nav.primary')} className={styles.bottomNav} data-testid="bottom-nav">
      <ul className={styles.bottomNavList}>
        {items.map((item) => (
          <li key={item.key}>
            <NavLink to={item.to} end={item.end} className={styles.bottomLink}>
              <Icon name={item.icon} size={22} />
              <span>{item.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
