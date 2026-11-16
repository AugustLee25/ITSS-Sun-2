import { Link, useLocation, useParams } from 'react-router'
import { paths } from '@/app/routes'
import { PageHeader } from '@/components'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { CreateGroup } from './CreateGroup'
import { GroupSettings } from './GroupSettings'
import { JoinGroup } from './JoinGroup'
import styles from './GroupSetup.module.css'

/**
 * S02 phục vụ bốn route: `/groups/new` (tạo), `/groups/join` và `/join/:code` (tham gia),
 * `/groups/:groupId/settings` (cài đặt nhóm của TL — trong khung nhóm).
 */
export default function S02GroupSetup() {
  const t = useT()
  const { groupId, code } = useParams()
  const { pathname } = useLocation()

  if (groupId) return <GroupSettings />

  const joining = !!code || pathname === paths.groupJoin()
  return (
    <>
      <PageHeader title={t('screens.S02')} backTo={paths.groups()} />
      <nav aria-label={t('groupSetup.modeLabel')} className={styles.modeTabs}>
        <Link to={paths.groupNew()} aria-current={joining ? undefined : 'page'} className={cx(styles.modeTab, !joining && styles.modeTabActive)}>
          {t('groupSetup.tabCreate')}
        </Link>
        <Link to={paths.groupJoin()} aria-current={joining ? 'page' : undefined} className={cx(styles.modeTab, joining && styles.modeTabActive)}>
          {t('groupSetup.tabJoin')}
        </Link>
      </nav>
      <div className={styles.wrap}>{joining ? <JoinGroup key={code ?? 'manual'} initialCode={code} /> : <CreateGroup />}</div>
    </>
  )
}
