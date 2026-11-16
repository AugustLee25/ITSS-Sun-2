import type { ScreenTarget } from '@/api/types'
import { pathForTarget, paths } from '@/app/routes'

/** Đích điều hướng do server gợi ý → URL. S03 + `cycleId` mở khu TL kiểm tra kỳ; S05 + `planId` mở bản đề xuất. */
export function targetHref(target: ScreenTarget): string {
  const base = pathForTarget(target)
  const { screen, params } = target
  if (screen === 'S03' && params.groupId && params.cycleId) return `${paths.group(params.groupId)}?cycleId=${encodeURIComponent(params.cycleId)}`
  if (screen === 'S05' && params.planId) return `${base}?planId=${encodeURIComponent(params.planId)}`
  return base
}
