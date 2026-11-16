import { useLocation, useParams } from 'react-router'
import { EmptyState, PageHeader, type IconName } from '@/components'
import { useT, type Messages } from '@/i18n'

/**
 * Trang giữ chỗ dùng cho S00–S13 cho tới khi agent phụ trách màn hình thay nội dung.
 * Ở DEV hiển thị tham số route để kiểm tra điều hướng.
 */
export function ScreenPlaceholder({ screen, icon = 'inbox' }: { screen: keyof Messages['screens']; icon?: IconName }) {
  const t = useT()
  const params = useParams()
  const { search } = useLocation()
  const debug = import.meta.env.DEV ? [...Object.entries(params).map(([k, v]) => `${k}=${v}`), search].filter(Boolean).join('  ') : ''
  return (
    <>
      <PageHeader title={t(`screens.${screen}`)} />
      <EmptyState
        icon={icon}
        title={t('placeholder.title')}
        description={debug ? `${t('placeholder.description')} (${debug})` : t('placeholder.description')}
      />
    </>
  )
}
