import { Button } from '@/components'
import { useT } from '@/i18n'
import styles from './Points.module.css'

/** Phân trang đơn giản cho danh sách task / sổ điểm (trang bắt đầu từ 0). */
export function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  const t = useT()
  if (totalPages <= 1) return null
  return (
    <nav className={styles.pager} aria-label={t('myPoints.history.page', { page: page + 1, total: totalPages })}>
      <Button variant="secondary" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        {t('myPoints.prev')}
      </Button>
      <span aria-current="page">{t('myPoints.history.page', { page: page + 1, total: totalPages })}</span>
      <Button variant="secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        {t('myPoints.next')}
      </Button>
    </nav>
  )
}
