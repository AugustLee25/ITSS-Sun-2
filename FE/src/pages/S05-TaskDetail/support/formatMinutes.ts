import type { TFunction } from '@/i18n'

/** `95` → "1 giờ 35 phút". Chỉ đổi đơn vị hiển thị của số phút tự khai. */
export function formatMinutes(t: TFunction, total: number): string {
  const hours = Math.floor(total / 60)
  const minutes = total % 60
  return hours > 0 ? t('supportWork.duration', { hours, minutes }) : t('supportWork.durationMinutes', { minutes })
}
