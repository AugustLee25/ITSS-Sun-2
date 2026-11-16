import type { IconName } from '../Icon/Icon'

/**
 * Tông màu ngữ nghĩa. Màu KHÔNG bao giờ là tín hiệu duy nhất: chip luôn có `label` chữ và biểu tượng.
 * Ánh xạ gợi ý: success = hoàn thành/đã duyệt; warning = trễ 1–2 task/cần xem xét; danger = cần xử lý;
 * pending = chờ duyệt/chờ xác nhận; info = đang thực hiện/trung tính tích cực; neutral = mặc định; muted = chưa có dữ liệu.
 */
export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'pending' | 'muted'

export const TONE_ICONS: Record<StatusTone, IconName> = {
  neutral: 'dot',
  info: 'info',
  success: 'check',
  warning: 'alert',
  danger: 'x-circle',
  pending: 'hourglass',
  muted: 'minus-circle',
}

