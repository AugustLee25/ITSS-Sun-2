import { Button, FormField, Input, useToast } from '@/components'
import { useT } from '@/i18n'
import { formatDateTime } from '@/lib/format'
import { copyText } from './clipboard'
import styles from './GroupSetup.module.css'

/** Mã + link mời với nút `Sao chép` (spec S02: modal thành công; cũng dùng ở cài đặt nhóm). */
export function InviteShare({
  code,
  sharePath,
  expiresAt,
  timeZone,
}: {
  code: string
  sharePath: string
  expiresAt?: string | null
  timeZone: string
}) {
  const t = useT()
  const toast = useToast()
  const link = `${window.location.origin}${sharePath}`

  async function copy(text: string) {
    if (await copyText(text)) toast.success(t('groupSetup.copied'))
    else toast.error(t('groupSetup.copyFailed'))
  }

  return (
    <div className={styles.share}>
      <div className={styles.shareRow}>
        <FormField label={t('groupSetup.inviteCode')}>
          <Input readOnly value={code} className={styles.code} onFocus={(event) => event.currentTarget.select()} />
        </FormField>
        <Button variant="secondary" onClick={() => void copy(code)} aria-label={`${t('groupSetup.copy')} — ${t('groupSetup.inviteCode')}`}>
          {t('groupSetup.copy')}
        </Button>
      </div>
      <div className={styles.shareRow}>
        <FormField label={t('groupSetup.inviteLink')}>
          <Input readOnly value={link} onFocus={(event) => event.currentTarget.select()} />
        </FormField>
        <Button variant="secondary" onClick={() => void copy(link)} aria-label={`${t('groupSetup.copy')} — ${t('groupSetup.inviteLink')}`}>
          {t('groupSetup.copy')}
        </Button>
      </div>
      {expiresAt ? <p className={styles.muted}>{t('groupSetup.inviteExpires', { time: formatDateTime(expiresAt, timeZone) })}</p> : null}
    </div>
  )
}
