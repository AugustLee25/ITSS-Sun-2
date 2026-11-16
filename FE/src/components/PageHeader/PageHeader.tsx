import { Fragment, useEffect, type ReactNode } from 'react'
import { Link } from 'react-router'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { Icon } from '../Icon/Icon'
import styles from './PageHeader.module.css'

export interface Crumb {
  label: string
  /** Bỏ trống cho mục hiện tại (cuối cùng). */
  to?: string
}

export interface PageHeaderProps {
  title: string
  /** Breadcrumb cho desktop: `Công việc / Tên task`. Mục cuối là trang hiện tại. */
  breadcrumbs?: Crumb[]
  /** Mobile: nút quay lại + tiêu đề. Mặc định lấy crumb cha gần nhất có `to`. */
  backTo?: string
  description?: ReactNode
  /** Chip trạng thái cạnh tiêu đề. */
  status?: ReactNode
  /** Một hành động chính (spec 2.1). */
  actions?: ReactNode
  className?: string
}

/** Đầu trang chuẩn: breadcrumb (desktop) / nút quay lại (mobile), h1, trạng thái, hành động chính. Cũng đặt `document.title`. */
export function PageHeader({ title, breadcrumbs, backTo, description, status, actions, className }: PageHeaderProps) {
  const t = useT()
  useEffect(() => {
    const previous = document.title
    document.title = `${title} · ${t('app.name')}`
    return () => {
      document.title = previous
    }
  }, [title, t])

  const parent = breadcrumbs ? [...breadcrumbs].reverse().find((crumb) => crumb.to) : undefined
  const back = backTo ?? parent?.to

  return (
    <header className={cx(styles.header, className)}>
      {breadcrumbs && breadcrumbs.length > 0 ? (
        <nav aria-label={t('nav.breadcrumb')} className={styles.breadcrumbs}>
          <ol>
            {breadcrumbs.map((crumb, index) => {
              const isLast = index === breadcrumbs.length - 1
              return (
                <Fragment key={`${index}-${crumb.label}`}>
                  <li>
                    {crumb.to && !isLast ? (
                      <Link to={crumb.to}>{crumb.label}</Link>
                    ) : (
                      <span aria-current={isLast ? 'page' : undefined}>{crumb.label}</span>
                    )}
                  </li>
                  {!isLast ? (
                    <li aria-hidden="true" className={styles.sep}>
                      /
                    </li>
                  ) : null}
                </Fragment>
              )
            })}
          </ol>
        </nav>
      ) : null}
      <div className={styles.row}>
        <div className={styles.titleWrap}>
          {back ? (
            <Link to={back} className={styles.back} aria-label={t('common.back')}>
              <Icon name="chevron-left" size={24} />
            </Link>
          ) : null}
          <h1 className={styles.title}>{title}</h1>
          {status ? <div className={styles.status}>{status}</div> : null}
        </div>
        {actions ? <div className={styles.actions}>{actions}</div> : null}
      </div>
      {description ? <p className={styles.description}>{description}</p> : null}
    </header>
  )
}
