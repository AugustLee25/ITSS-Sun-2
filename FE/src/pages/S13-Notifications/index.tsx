import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useMarkAllRead, useMarkRead, useNotifications, useOpenNotification } from '@/api/notifications'
import { NotificationTab, type NotificationItem as Notification } from '@/api/types'
import { Button, EmptyState, InlineError, LoadingSkeleton, NotificationItem, PageHeader, useToast } from '@/components'
import { useT } from '@/i18n'
import { targetHref } from '@/pages/S03-Overview/targets'
import { TabBar } from '@/pages/S04-Tasks/TabBar'
import styles from './Notifications.module.css'

const TABS = [NotificationTab.ACTION_REQUIRED, NotificationTab.INFO] as const
const PAGE_SIZE = 20

/** S13 — Thông báo: tab Cần hành động / Thông tin, chưa đọc, mở = `open` rồi đi tới đích đã kiểm tra quyền. */
export default function S13Notifications() {
  const t = useT()
  const toast = useToast()
  const navigate = useNavigate()
  const [tab, setTab] = useState<NotificationTab>(NotificationTab.ACTION_REQUIRED)
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(0)
  const list = useNotifications({ tab, unreadOnly: unreadOnly || undefined, page, size: PAGE_SIZE })
  const open = useOpenNotification()
  const markRead = useMarkRead()
  const markAll = useMarkAllRead()
  const items = list.data?.items ?? []

  function activate(item: Notification) {
    if (open.isPending) return
    open.mutate(item.id, {
      onSuccess: (result) => {
        if (result.available) {
          navigate(targetHref(result.target))
        } else {
          toast.info(result.unavailableMessage ?? t('notifications.unavailable'))
          if (result.fallback) navigate(targetHref(result.fallback))
        }
      },
      onError: () => toast.error(t('notifications.openFailed')),
    })
  }

  return (
    <>
      <PageHeader
        title={t('screens.S13')}
        actions={
          <Button
            variant="secondary"
            loading={markAll.isPending}
            onClick={() => markAll.mutate({ tab }, { onSuccess: () => toast.success(t('notifications.markedAll')) })}
          >
            {t('notifications.markAllRead')}
          </Button>
        }
      />
      <TabBar
        label={t('notifications.tabsLabel')}
        panelId="notification-panel"
        value={tab}
        onChange={(next) => {
          setTab(next)
          setPage(0)
        }}
        items={TABS.map((value) => ({ value, label: value === NotificationTab.ACTION_REQUIRED ? t('notifications.tabs.action') : t('notifications.tabs.info') }))}
      />
      <label className={styles.filter}>
        <input
          type="checkbox"
          checked={unreadOnly}
          onChange={(event) => {
            setUnreadOnly(event.target.checked)
            setPage(0)
          }}
        />
        <span>{t('notifications.unreadOnly')}</span>
      </label>

      <div id="notification-panel" role="tabpanel" aria-labelledby={`notification-panel-tab-${tab}`}>
        {list.isLoading ? <LoadingSkeleton variant="list" count={4} /> : null}
        {list.isError ? <InlineError error={list.error} title={t('notifications.loadFailed')} onRetry={() => void list.refetch()} /> : null}
        {markAll.isError || markRead.isError ? <InlineError error={markAll.error ?? markRead.error} /> : null}
        {list.data && items.length === 0 ? <EmptyState icon="bell" title={unreadOnly ? t('notifications.emptyUnread') : t('notifications.empty')} /> : null}
        {items.length > 0 ? (
          <ul className={styles.list}>
            {items.map((item) => (
              <li key={item.id} className={styles.row}>
                <div className={styles.item}>
                  <NotificationItem
                    type={item.type}
                    title={item.title}
                    context={[item.groupName, item.subjectLabel].filter(Boolean).join(' · ')}
                    priority={item.priority}
                    createdAt={item.createdAt}
                    read={item.isRead}
                    onActivate={() => activate(item)}
                  />
                </div>
                {!item.isRead ? (
                  <Button variant="ghost" loading={markRead.isPending && markRead.variables === item.id} onClick={() => markRead.mutate(item.id)}>
                    {t('notifications.markRead')}
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : null}
        {list.data && list.data.totalPages > 1 ? (
          <nav className={styles.pager} aria-label={t('helpBoard.pager')}>
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              {t('common.previous')}
            </Button>
            <span>{t('helpBoard.pageOf', { page: list.data.page + 1, total: list.data.totalPages })}</span>
            <Button variant="secondary" disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(page + 1)}>
              {t('common.next')}
            </Button>
          </nav>
        ) : null}
      </div>
    </>
  )
}
