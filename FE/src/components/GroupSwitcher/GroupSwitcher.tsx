import { useId } from 'react'
import { Link } from 'react-router'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { Icon } from '../Icon/Icon'
import { usePopover } from '../Popover/usePopover'
import styles from './GroupSwitcher.module.css'

export interface GroupSwitcherItem {
  id: string
  name: string
  /** Nhãn vai trò đã dịch (vd. "Trưởng nhóm"). */
  roleLabel?: string
}

export interface GroupSwitcherProps {
  groups: GroupSwitcherItem[]
  currentId?: string
  /** Tên nhóm hiện tại (dùng khi danh sách chưa tải xong). */
  currentName?: string
  /** URL S03 của một nhóm, vd. `paths.group`. */
  hrefFor: (groupId: string) => string
  /** URL S01 (danh sách nhóm). */
  allGroupsHref: string
  /** URL S02 (tạo / tham gia nhóm). */
  createHref: string
  className?: string
}

/** Chọn nhóm đang xem ở header (spec 1.2). Mỗi nhóm là một link, nhóm hiện tại có aria-current. */
export function GroupSwitcher({ groups, currentId, currentName, hrefFor, allGroupsHref, createHref, className }: GroupSwitcherProps) {
  const t = useT()
  const panelId = useId()
  const { open, setOpen, close, triggerRef, panelRef } = usePopover()
  const name = currentName ?? groups.find((g) => g.id === currentId)?.name ?? t('nav.groups')

  return (
    <div className={cx(styles.root, className)}>
      <button
        ref={triggerRef}
        type="button"
        className={styles.trigger}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={`${t('nav.switchGroup')}: ${name}`}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="users" size={18} />
        <span className={styles.name}>{name}</span>
        <Icon name="chevron-down" size={18} />
      </button>
      {open ? (
        <div id={panelId} ref={panelRef} className={styles.panel}>
          {groups.length > 0 ? (
            <ul className={styles.list} aria-label={t('nav.groups')}>
              {groups.map((group) => (
                <li key={group.id}>
                  <Link
                    to={hrefFor(group.id)}
                    className={styles.item}
                    aria-current={group.id === currentId ? 'true' : undefined}
                    onClick={() => close()}
                  >
                    <span className={styles.itemName}>{group.name}</span>
                    {group.roleLabel ? <span className={styles.role}>{group.roleLabel}</span> : null}
                    {group.id === currentId ? <Icon name="check" size={18} /> : null}
                  </Link>
                </li>
              ))}
            </ul>
          ) : null}
          <div className={styles.footer}>
            <Link to={allGroupsHref} className={styles.item} onClick={() => close()}>
              {t('nav.allGroups')}
            </Link>
            <Link to={createHref} className={styles.item} onClick={() => close()}>
              <Icon name="plus" size={18} />
              {t('nav.createOrJoinGroup')}
            </Link>
          </div>
        </div>
      ) : null}
    </div>
  )
}
