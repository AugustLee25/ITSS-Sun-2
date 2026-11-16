import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useHelpRequests } from '@/api/help'
import { Difficulty, HelpRequestTab, type ListHelpRequestsQuery } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import { Button, ButtonLink, EmptyState, FormField, HelpRequestCard, InlineError, Input, LoadingSkeleton, PageHeader, Select, useStatusChip } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import { zonedTimeToIso } from '@/lib/zonedTime'
import { TabBar } from '@/pages/S04-Tasks/TabBar'
import { CreateHelpDialog } from './CreateHelpDialog'
import styles from './HelpBoard.module.css'

const TABS = [HelpRequestTab.OPEN, HelpRequestTab.MY_OFFERS, HelpRequestTab.MY_WORK, HelpRequestTab.COMPLETED] as const
const PAGE_SIZE = 20
/** 1 điểm % = 10.000 units (hợp đồng 1.5); chỉ đổi đơn vị ô lọc, không tính điểm. */
const UNITS_PER_POINT = 10_000

interface Filters {
  difficulty: string
  deadlineTo: string
  minPoints: string
  maxEffort: string
}
const NO_FILTERS: Filters = { difficulty: '', deadlineTo: '', minPoints: '', maxEffort: '' }

/** S08 — Bảng hỗ trợ: yêu cầu công khai do TL đăng (tự nguyện đề nghị), 4 tab + lọc; TL có `+ Đăng yêu cầu`. */
export default function S08HelpBoard() {
  const t = useT()
  const f = useFormatters()
  const chip = useStatusChip()
  const { groupId, isLeader } = useGroup()
  const [params, setParams] = useSearchParams()
  const [tab, setTab] = useState<HelpRequestTab>(HelpRequestTab.OPEN)
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState(NO_FILTERS)
  const creating = isLeader && params.get('create') === '1'

  const query: ListHelpRequestsQuery = { tab, page, size: PAGE_SIZE }
  if (filters.difficulty) query.difficulty = filters.difficulty as Difficulty
  if (filters.deadlineTo) query.deadlineTo = zonedTimeToIso(filters.deadlineTo, '23:59', f.timeZone) ?? undefined
  if (filters.minPoints && Number(filters.minPoints) >= 0) query.minPointUnits = Math.round(Number(filters.minPoints) * UNITS_PER_POINT)
  if (filters.maxEffort && Number(filters.maxEffort) > 0) query.maxEffortMinutes = Number(filters.maxEffort)
  const list = useHelpRequests(groupId, query)
  const items = list.data?.items ?? []
  const activeFilters = Object.values(filters).some(Boolean)

  const setFilter = (patch: Partial<Filters>) => {
    setFilters((current) => ({ ...current, ...patch }))
    setPage(0)
  }
  const openCreate = () => setParams({ create: '1' }, { preventScrollReset: true })
  const closeCreate = () => setParams({}, { preventScrollReset: true, replace: true })

  return (
    <>
      <PageHeader
        title={t('helpBoard.title')}
        description={t('helpBoard.description')}
        actions={
          isLeader ? (
            <Button icon="plus" onClick={openCreate}>
              {t('helpBoard.create')}
            </Button>
          ) : (
            <ButtonLink to={paths.tasks(groupId)} variant="secondary">
              {t('helpBoard.askLead')}
            </ButtonLink>
          )
        }
      />

      <TabBar
        label={t('helpBoard.tabsLabel')}
        panelId="help-panel"
        value={tab}
        onChange={(next) => {
          setTab(next)
          setPage(0)
        }}
        items={TABS.map((value) => ({ value, label: t(`helpBoard.tabs.${value}` as MessageKey) }))}
      />

      <form className={styles.filters} aria-label={t('helpBoard.filters')} onSubmit={(event) => event.preventDefault()}>
        <FormField label={t('fields.difficulty')}>
          <Select value={filters.difficulty} onChange={(event) => setFilter({ difficulty: event.target.value })}>
            <option value="">{t('common.all')}</option>
            {Object.values(Difficulty).map((value) => (
              <option key={value} value={value}>
                {t(`status.difficulty.${value}`)}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label={t('helpBoard.filterDeadline')}>
          <Input type="date" value={filters.deadlineTo} onChange={(event) => setFilter({ deadlineTo: event.target.value })} />
        </FormField>
        <FormField label={t('helpBoard.filterPoints')}>
          <Input type="number" inputMode="decimal" min={0} step="any" value={filters.minPoints} onChange={(event) => setFilter({ minPoints: event.target.value })} />
        </FormField>
        <FormField label={t('helpBoard.filterEffort')}>
          <Input type="number" inputMode="numeric" min={1} value={filters.maxEffort} onChange={(event) => setFilter({ maxEffort: event.target.value })} />
        </FormField>
        {activeFilters ? (
          <div>
            <Button variant="ghost" onClick={() => setFilter(NO_FILTERS)}>
              {t('helpBoard.clearFilters')}
            </Button>
          </div>
        ) : null}
      </form>

      <div id="help-panel" role="tabpanel" aria-labelledby={`help-panel-tab-${tab}`}>
        {list.isLoading ? <LoadingSkeleton variant="list" count={4} /> : null}
        {list.isError ? <InlineError error={list.error} title={t('helpBoard.loadFailed')} onRetry={() => void list.refetch()} /> : null}
        {list.data && items.length === 0 ? (
          <EmptyState
            icon="help"
            title={activeFilters ? t('helpBoard.emptyFiltered') : t(`helpBoard.empty.${tab}` as MessageKey)}
            description={tab === HelpRequestTab.OPEN && !activeFilters ? t('helpBoard.emptyOpen') : undefined}
            action={
              tab === HelpRequestTab.OPEN && !activeFilters && isLeader ? (
                <Button icon="plus" onClick={openCreate}>
                  {t('helpBoard.create')}
                </Button>
              ) : undefined
            }
          />
        ) : null}
        {items.length > 0 ? (
          <ul className={styles.list}>
            {items.map((request) => (
              <li key={request.id}>
                <HelpRequestCard
                  title={request.title}
                  href={paths.helpRequest(groupId, request.id)}
                  taskTitle={request.task.title}
                  requesterName={request.postedBy.name}
                  difficultyLabel={t(`status.difficulty.${request.difficulty}`)}
                  pointsLabel={request.points ? t('helpBoard.pointsAfterApproval', { points: f.pointsLabel(request.points, { signed: true }) }) : undefined}
                  effortLabel={request.estimatedEffortMinutes ? t('helpBoard.effortMinutes', { minutes: request.estimatedEffortMinutes }) : undefined}
                  deadline={request.deadline}
                  status={{ tone: chip('help', request.status).tone, label: request.statusLabel }}
                  chips={request.myOfferStatus ? [chip('offer', request.myOfferStatus)] : undefined}
                  extraMeta={[
                    { label: t('helpBoard.beneficiary'), value: request.beneficiary.name },
                    { label: t('helpBoard.summary'), value: request.descriptionPreview },
                    { label: t('helpBoard.output'), value: request.expectedOutput },
                  ]}
                  actions={
                    <ButtonLink to={paths.helpRequest(groupId, request.id)} variant="secondary">
                      {t('common.viewDetail')}
                    </ButtonLink>
                  }
                />
              </li>
            ))}
          </ul>
        ) : null}
        {list.data && list.data.totalPages > 1 ? (
          <nav className={styles.pager} aria-label={t('helpBoard.pager')}>
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              {t('common.previous')}
            </Button>
            <span>{t('helpBoard.pageOf', { page: list.data.page + 1, total: list.data.totalPages })}</span>
            <Button variant="secondary" disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(page + 1)}>
              {t('common.next')}
            </Button>
          </nav>
        ) : null}
      </div>

      {creating ? (
        <CreateHelpDialog
          groupId={groupId}
          initialTaskId={params.get('taskId') ?? undefined}
          supportRequestId={params.get('supportRequestId') ?? undefined}
          onClose={closeCreate}
        />
      ) : null}
    </>
  )
}
