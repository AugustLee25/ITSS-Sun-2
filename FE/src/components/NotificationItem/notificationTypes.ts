import { NotificationType } from '@/api/types'
import type { IconName } from '../Icon/Icon'

/** Biểu tượng theo loại thông báo (đủ mọi giá trị `NotificationType` của hợp đồng API). */
export const NOTIFICATION_ICONS: Record<NotificationType, IconName> = {
  [NotificationType.TASK_ASSIGNED]: 'tasks',
  [NotificationType.PLAN_SUBMITTED]: 'file-text',
  [NotificationType.PLAN_CHANGE_SUBMITTED]: 'file-text',
  [NotificationType.PLAN_APPROVED]: 'check',
  [NotificationType.PLAN_CHANGES_REQUESTED]: 'edit',
  [NotificationType.CHECKPOINT_REMINDER]: 'clock',
  [NotificationType.CHECKPOINT_REVIEW_REQUIRED]: 'calendar',
  [NotificationType.CHECKPOINT_SCHEDULE_CHANGED]: 'calendar',
  [NotificationType.NEED_HELP]: 'help',
  [NotificationType.SUPPORT_DECISION]: 'help',
  [NotificationType.HELP_PUBLISHED]: 'help',
  [NotificationType.RISK_RELEVANT]: 'alert',
  [NotificationType.SUPPORT_OFFERED]: 'help',
  [NotificationType.SUPPORT_ACCEPTED]: 'check',
  [NotificationType.SUPPORT_SUBMITTED]: 'send',
  [NotificationType.REVISION_REQUESTED]: 'edit',
  [NotificationType.CONTRIBUTION_CONFIRMED]: 'award',
  [NotificationType.POINTS_AWARDED]: 'coins',
  [NotificationType.POINT_ASSIGNMENT_CHANGED]: 'coins',
  [NotificationType.TASK_REVIEWED]: 'check',
  [NotificationType.TASK_CARRIED_OVER]: 'arrow-right',
  [NotificationType.SUPPORT_NOT_SELECTED]: 'info',
  [NotificationType.SUPPORT_WORK_ISSUE]: 'alert',
  [NotificationType.POINTS_ADJUSTED]: 'coins',
  [NotificationType.POINT_POLICY_ACTIVATED]: 'settings',
}
