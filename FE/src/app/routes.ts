import type { ScreenTarget } from '@/api/types'

/**
 * Nguồn sự thật cho URL của ứng dụng. Link tới màn hình khác LUÔN dùng `paths.*`,
 * không tự ghép chuỗi. Mọi URL mở trực tiếp được (spec 1.1); S06/S07 là route modal có link riêng.
 * Tên tham số route trùng khóa `ScreenTarget.params` của API (groupId, taskId, cycleId, supportRequestId, helpRequestId).
 */
const enc = encodeURIComponent

export const paths = {
  root: () => '/',
  login: () => '/login',
  groups: () => '/groups', // S01
  groupNew: () => '/groups/new', // S02 (tạo nhóm)
  /** S02 (tham gia nhóm). Có mã → link mời `/join/:code` (khớp `Invite.sharePath` của BE). */
  groupJoin: (code?: string) => (code ? `/join/${enc(code)}` : '/groups/join'),
  notifications: () => '/notifications', // S13
  forbidden: () => '/403',

  group: (groupId: string) => `/groups/${enc(groupId)}`, // S03
  groupSettings: (groupId: string) => `/groups/${enc(groupId)}/settings`, // cài đặt nhóm (Leader)
  tasks: (groupId: string) => `/groups/${enc(groupId)}/tasks`, // S04
  task: (groupId: string, taskId: string) => `/groups/${enc(groupId)}/tasks/${enc(taskId)}`, // S05
  taskCheckpoint: (groupId: string, taskId: string) => `/groups/${enc(groupId)}/tasks/${enc(taskId)}/checkpoint`, // S06 (từ S05)
  checkpoint: (groupId: string, cycleId: string) => `/groups/${enc(groupId)}/checkpoints/${enc(cycleId)}`, // S06 (từ S03/S13, theo kỳ)
  taskDifficulty: (groupId: string, taskId: string) => `/groups/${enc(groupId)}/tasks/${enc(taskId)}/difficulty`, // S07 (thành viên báo)
  difficulty: (groupId: string, supportRequestId: string) => `/groups/${enc(groupId)}/difficulty/${enc(supportRequestId)}`, // S07 (TL xử lý / xem của mình)
  help: (groupId: string) => `/groups/${enc(groupId)}/help`, // S08
  helpRequest: (groupId: string, helpRequestId: string) => `/groups/${enc(groupId)}/help/${enc(helpRequestId)}`, // S09
  /** Link cũ (S10/S11 đã bỏ): chỉ còn là đường chuyển hướng về chi tiết task. Không dùng để tạo link mới. */
  helpWork: (groupId: string, helpRequestId: string) => `/groups/${enc(groupId)}/help/${enc(helpRequestId)}/work`,
  helpReview: (groupId: string, helpRequestId: string) => `/groups/${enc(groupId)}/help/${enc(helpRequestId)}/review`,
  points: (groupId: string) => `/groups/${enc(groupId)}/points`, // S12
}

/** Mẫu route (tương đối) dùng trong `router.tsx`. */
export const patterns = {
  login: 'login',
  groups: 'groups',
  groupNew: 'groups/new',
  groupJoin: 'groups/join',
  joinByCode: 'join/:code',
  notifications: 'notifications',
  forbidden: '403',
  group: 'groups/:groupId',
  settings: 'settings',
  tasks: 'tasks',
  task: 'tasks/:taskId',
  taskCheckpoint: 'tasks/:taskId/checkpoint',
  checkpoint: 'checkpoints/:cycleId',
  taskDifficulty: 'tasks/:taskId/difficulty',
  difficulty: 'difficulty/:supportRequestId',
  help: 'help',
  helpRequest: 'help/:helpRequestId',
  helpWork: 'help/:helpRequestId/work',
  helpReview: 'help/:helpRequestId/review',
  points: 'points',
} as const

/**
 * Đổi mục tiêu điều hướng do server gợi ý (`NotificationItem.target`, CTA ở S03...) thành URL.
 * Thiếu tham số cần thiết → rơi về trang chủ nhóm (hoặc S01 nếu cũng thiếu groupId).
 */
export function pathForTarget(target: ScreenTarget): string {
  const p = target.params
  const g = p.groupId
  const fallback = g ? paths.group(g) : paths.groups()
  switch (target.screen) {
    case 'S00':
      return paths.login()
    case 'S01':
      return paths.groups()
    case 'S02':
      return paths.groupNew()
    case 'S03':
      return fallback
    case 'S04':
      return g ? paths.tasks(g) : fallback
    case 'S05':
      return g && p.taskId ? paths.task(g, p.taskId) : fallback
    case 'S06':
      if (g && p.cycleId) return paths.checkpoint(g, p.cycleId)
      return g && p.taskId ? paths.taskCheckpoint(g, p.taskId) : fallback
    case 'S07':
      if (g && p.supportRequestId) return paths.difficulty(g, p.supportRequestId)
      return g && p.taskId ? paths.taskDifficulty(g, p.taskId) : fallback
    case 'S08':
      return g ? paths.help(g) : fallback
    case 'S09':
      return g && p.helpRequestId ? paths.helpRequest(g, p.helpRequestId) : fallback
    // Solution 2: không còn màn thực hiện/nghiệm thu hỗ trợ riêng — thông báo S10/S11 dẫn về chính task (S05).
    // Chỉ khi thông báo không mang `taskId` mới dùng đường dẫn cũ, và đường dẫn đó tự chuyển hướng về task.
    case 'S10':
    case 'S11': {
      if (g && p.taskId) return paths.task(g, p.taskId)
      const key = p.helpRequestId ?? p.workId
      if (!g || !key) return fallback
      return target.screen === 'S10' ? paths.helpWork(g, key) : paths.helpReview(g, key)
    }
    case 'S12': {
      if (!g) return fallback
      const q = new URLSearchParams()
      for (const k of ['tab', 'ledgerEntryId', 'taskId'] as const) if (p[k]) q.set(k, p[k])
      return q.size ? `${paths.points(g)}?${q}` : paths.points(g)
    }
    case 'S13':
      return paths.notifications()
  }
}
