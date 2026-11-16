import { useEffect } from 'react'
import { useBlocker } from 'react-router'
import { useT } from '@/i18n'
import { ConfirmDialog } from '../Dialog/ConfirmDialog'

/**
 * Hỏi xác nhận khi rời màn hình có dữ liệu chưa lưu (spec 2.4 #5). Đặt trong form: `<UnsavedChangesGuard when={formState.isDirty} />`.
 * Không hỏi khi form trống/không đổi. Nhớ đặt `when=false` (reset form) TRƯỚC khi điều hướng sau khi lưu thành công.
 */
export function UnsavedChangesGuard({ when }: { when: boolean }) {
  const t = useT()
  const blocker = useBlocker(({ currentLocation, nextLocation }) => when && currentLocation.pathname !== nextLocation.pathname)

  useEffect(() => {
    if (!when) return
    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault()
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [when])

  return (
    <ConfirmDialog
      open={blocker.state === 'blocked'}
      title={t('form.unsavedTitle')}
      description={t('form.unsavedDescription')}
      confirmLabel={t('form.unsavedLeave')}
      cancelLabel={t('form.unsavedStay')}
      tone="danger"
      onConfirm={() => blocker.proceed?.()}
      onCancel={() => blocker.reset?.()}
    />
  )
}
