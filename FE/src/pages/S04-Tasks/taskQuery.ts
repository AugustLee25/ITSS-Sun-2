import {
  Difficulty,
  PlanStatus,
  TaskListTab,
  TaskStatus,
  type ListTasksQuery,
} from '@/api/types'
import { zonedTimeToIso } from '@/lib/zonedTime'

export const PAGE_SIZE = 20

/** Bộ lọc S04 giữ trong URL (chia sẻ được, nút Back hoạt động). Giá trị rỗng = không lọc. */
export interface TaskFilters {
  tab: TaskListTab
  cycleId: string
  assigneeId: string
  planStatus: string
  taskStatus: string
  difficulty: string
  q: string
  from: string
  to: string
  page: number
}

const oneOf = (values: Record<string, string>, raw: string | null): string => (raw && raw in values ? raw : '')

export function parseFilters(params: URLSearchParams): TaskFilters {
  const tab = params.get('tab')
  const page = Number(params.get('page') ?? '0')
  return {
    tab: tab && tab in TaskListTab ? (tab as TaskListTab) : TaskListTab.ALL,
    cycleId: params.get('cycle') ?? '',
    assigneeId: params.get('assignee') ?? '',
    planStatus: oneOf(PlanStatus, params.get('plan')),
    taskStatus: oneOf(TaskStatus, params.get('status')),
    difficulty: oneOf(Difficulty, params.get('difficulty')),
    q: params.get('q') ?? '',
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    page: Number.isInteger(page) && page > 0 ? page : 0,
  }
}

export function filtersToParams(filters: TaskFilters): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.tab !== TaskListTab.ALL) params.set('tab', filters.tab)
  if (filters.cycleId) params.set('cycle', filters.cycleId)
  if (filters.assigneeId) params.set('assignee', filters.assigneeId)
  if (filters.planStatus) params.set('plan', filters.planStatus)
  if (filters.taskStatus) params.set('status', filters.taskStatus)
  if (filters.difficulty) params.set('difficulty', filters.difficulty)
  if (filters.q.trim()) params.set('q', filters.q.trim())
  if (filters.from) params.set('from', filters.from)
  if (filters.to) params.set('to', filters.to)
  if (filters.page > 0) params.set('page', String(filters.page))
  return params
}

/** Số bộ lọc đang bật (không tính tab và trang). */
export function activeFilterCount(filters: TaskFilters): number {
  return [filters.cycleId, filters.assigneeId, filters.planStatus, filters.taskStatus, filters.difficulty, filters.q.trim(), filters.from, filters.to].filter(
    Boolean,
  ).length
}

/** Đổi bộ lọc thành query của `GET /groups/{id}/tasks`. Ngày deadline (múi giờ nhóm) → UTC ISO. */
export function toListQuery(filters: TaskFilters, timeZone: string): ListTasksQuery {
  const query: ListTasksQuery = { tab: filters.tab, page: filters.page, size: PAGE_SIZE }
  if (filters.cycleId) query.cycleId = filters.cycleId
  if (filters.assigneeId) query.assigneeId = filters.assigneeId
  if (filters.planStatus) query.planStatus = filters.planStatus as PlanStatus
  if (filters.taskStatus) query.taskStatus = filters.taskStatus as TaskStatus
  if (filters.difficulty) query.difficulty = filters.difficulty as Difficulty
  if (filters.q.trim()) query.q = filters.q.trim()
  const from = filters.from ? zonedTimeToIso(filters.from, '00:00', timeZone) : null
  const to = filters.to ? zonedTimeToIso(filters.to, '23:59:59', timeZone) : null
  if (from) query.deadlineFrom = from
  if (to) query.deadlineTo = to
  return query
}
