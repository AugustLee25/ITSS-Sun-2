import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useMembers } from '@/api/groups'
import { useCycles } from '@/api/schedule'
import { useTasks } from '@/api/tasks'
import {
  Difficulty,
  PlanStatus,
  TaskListTab,
  TaskStatus,
} from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { Button, EmptyState, FormField, InlineError, Input, LoadingSkeleton, PageHeader, Select } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { BalanceTable } from './BalanceTable'
import { CreateTaskDialog } from './CreateTaskDialog'
import { TabBar } from './TabBar'
import { TaskSummaryCard } from './TaskSummaryCard'
import { activeFilterCount, filtersToParams, parseFilters, toListQuery, type TaskFilters } from './taskQuery'
import styles from './Tasks.module.css'

const TAB_ORDER = [
  TaskListTab.ALL,
  TaskListTab.MINE,
  TaskListTab.PENDING_APPROVAL,
  TaskListTab.NEEDS_EDIT,
  TaskListTab.NEEDS_ATTENTION,
] as const

/** S04 — Công việc & Kế hoạch: danh sách task theo tab/bộ lọc (URL giữ trạng thái), TL giao task + bảng cân đối. */
export default function S04Tasks() {
  const t = useT()
  const { groupId, isLeader, timeZone } = useGroup()
  const [params, setParams] = useSearchParams()
  const filters = parseFilters(params)
  const [creating, setCreating] = useState(false)

  const tasks = useTasks(groupId, toListQuery(filters, timeZone))
  const members = useMembers(groupId)
  const cycles = useCycles(groupId)
  const cycleItems = cycles.data?.items ?? []
  const selectedCycle = cycleItems.find((cycle) => cycle.id === filters.cycleId)
  const filterCount = activeFilterCount(filters)
  const [filtersOpen, setFiltersOpen] = useState(filterCount > 0)

  function update(patch: Partial<TaskFilters>, resetPage = true) {
    setParams(filtersToParams({ ...filters, ...patch, page: resetPage ? 0 : (patch.page ?? filters.page) }), { preventScrollReset: true })
  }

  const page = tasks.data
  const items = page?.items ?? []

  return (
    <>
      <PageHeader
        title={t('tasks.title')}
        description={t('tasks.viewingWeek', { label: selectedCycle?.label ?? t('tasks.filterAll') })}
        actions={
          isLeader ? (
            <Button icon="plus" onClick={() => setCreating(true)}>
              {t('tasks.assign')}
            </Button>
          ) : undefined
        }
      />

      <TabBar
        label={t('tasks.tabsLabel')}
        panelId="task-panel"
        value={filters.tab}
        onChange={(tab) => update({ tab })}
        items={TAB_ORDER.map((tab) => ({ value: tab, label: t(`tasks.tabs.${tab}` as MessageKey) }))}
      />

      <section className={styles.filters} aria-label={t('tasks.filters')}>
        <Button
          variant="secondary"
          aria-expanded={filtersOpen}
          aria-controls="task-filter-body"
          onClick={() => setFiltersOpen((value) => !value)}
        >
          {filterCount > 0 ? t('tasks.filtersActive', { count: filterCount }) : t('tasks.filters')}
        </Button>
        {filterCount > 0 ? (
          <Button variant="ghost" onClick={() => update({ cycleId: '', assigneeId: '', planStatus: '', taskStatus: '', difficulty: '', q: '', from: '', to: '' })}>
            {t('tasks.filtersClear')}
          </Button>
        ) : null}
        {filtersOpen ? (
          <form
            id="task-filter-body"
            className={styles.filterGrid}
            onSubmit={(event) => {
              event.preventDefault()
              const q = new FormData(event.currentTarget).get('q')
              update({ q: typeof q === 'string' ? q : '' })
            }}
          >
            <FormField label={t('tasks.filterSearch')}>
              <Input key={filters.q} name="q" type="search" defaultValue={filters.q} onBlur={(event) => event.target.value !== filters.q && update({ q: event.target.value })} />
            </FormField>
            <FormField label={t('tasks.filterCycle')}>
              <Select value={filters.cycleId} onChange={(event) => update({ cycleId: event.target.value })}>
                <option value="">{t('tasks.filterAll')}</option>
                {cycleItems.map((cycle) => (
                  <option key={cycle.id} value={cycle.id}>
                    {cycle.label}
                    {cycle.isCurrent ? t('tasks.currentCycleSuffix') : ''}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label={t('tasks.filterAssignee')}>
              <Select value={filters.assigneeId} onChange={(event) => update({ assigneeId: event.target.value })}>
                <option value="">{t('tasks.filterAll')}</option>
                {members.data?.map((member) => (
                  <option key={member.userId} value={member.userId}>
                    {member.name}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label={t('tasks.filterPlan')}>
              <Select value={filters.planStatus} onChange={(event) => update({ planStatus: event.target.value })}>
                <option value="">{t('tasks.filterAll')}</option>
                {Object.values(PlanStatus).map((status) => (
                  <option key={status} value={status}>
                    {t(`status.plan.${status}`)}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label={t('tasks.filterExecution')}>
              <Select value={filters.taskStatus} onChange={(event) => update({ taskStatus: event.target.value })}>
                <option value="">{t('tasks.filterAll')}</option>
                {Object.values(TaskStatus).map((status) => (
                  <option key={status} value={status}>
                    {t(`status.task.${status}`)}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label={t('tasks.filterDifficulty')}>
              <Select value={filters.difficulty} onChange={(event) => update({ difficulty: event.target.value })}>
                <option value="">{t('tasks.filterAll')}</option>
                {Object.values(Difficulty).map((difficulty) => (
                  <option key={difficulty} value={difficulty}>
                    {t(`status.difficulty.${difficulty}`)}
                  </option>
                ))}
              </Select>
            </FormField>
            <FormField label={t('tasks.filterDeadlineFrom')}>
              <Input type="date" value={filters.from} onChange={(event) => update({ from: event.target.value })} />
            </FormField>
            <FormField label={t('tasks.filterDeadlineTo')}>
              <Input type="date" value={filters.to} min={filters.from || undefined} onChange={(event) => update({ to: event.target.value })} />
            </FormField>
          </form>
        ) : null}
      </section>

      <div id="task-panel" role="tabpanel" aria-labelledby={`task-panel-tab-${filters.tab}`} className={styles.panel}>
        {tasks.isPending ? (
          <LoadingSkeleton variant="list" count={3} label={t('states.loading')} />
        ) : tasks.isError ? (
          <InlineError error={tasks.error} title={t('tasks.loadError')} onRetry={() => void tasks.refetch()} />
        ) : items.length === 0 ? (
          filterCount > 0 || filters.tab !== TaskListTab.ALL ? (
            <EmptyState
              icon="search-x"
              title={t('tasks.emptyFilteredTitle')}
              description={t('tasks.emptyFilteredHint')}
              action={
                <Button
                  variant="secondary"
                  onClick={() =>
                    setParams(new URLSearchParams(), { preventScrollReset: true })
                  }
                >
                  {t('tasks.filtersClear')}
                </Button>
              }
            />
          ) : (
            <EmptyState
              icon="tasks"
              title={t('tasks.emptyTitle')}
              description={isLeader ? t('tasks.emptyLead') : t('tasks.emptyMember')}
              action={
                isLeader ? (
                  <Button icon="plus" onClick={() => setCreating(true)}>
                    {t('tasks.assign')}
                  </Button>
                ) : undefined
              }
            />
          )
        ) : (
          <>
            <p className={styles.muted} aria-live="polite">
              {t('tasks.resultCount', { count: page?.totalItems ?? items.length })}
            </p>
            <ul className={styles.list} aria-label={t('tasks.listLabel')}>
              {items.map((task) => (
                <li key={task.id}>
                  <TaskSummaryCard task={task} groupId={groupId} />
                </li>
              ))}
            </ul>
            {page && page.totalPages > 1 ? (
              <nav className={styles.pager} aria-label={t('tasks.pageInfo', { page: page.page + 1, total: page.totalPages })}>
                <Button variant="secondary" disabled={page.page <= 0} onClick={() => update({ page: page.page - 1 }, false)}>
                  {t('tasks.pagePrev')}
                </Button>
                <span aria-current="page">{t('tasks.pageInfo', { page: page.page + 1, total: page.totalPages })}</span>
                <Button variant="secondary" disabled={page.page + 1 >= page.totalPages} onClick={() => update({ page: page.page + 1 }, false)}>
                  {t('tasks.pageNext')}
                </Button>
              </nav>
            ) : null}
          </>
        )}
      </div>

      {isLeader ? <BalanceTable groupId={groupId} /> : null}

      {creating ? (
        <CreateTaskDialog
          groupId={groupId}
          members={members.data ?? []}
          cycles={cycleItems}
          timeZone={timeZone}
          onClose={() => setCreating(false)}
        />
      ) : null}
    </>
  )
}
