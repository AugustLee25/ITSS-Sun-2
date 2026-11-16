import { useState } from 'react'
import { useSearchParams } from 'react-router'
import { useSimulatePoints, useSimulationCandidates } from '@/api/points'
import { isApiError } from '@/api/errors'
import { ErrorCode, type SimulationCandidate } from '@/api/types'
import { useGroup } from '@/app/group/groupContext'
import { Button, Card, InlineError, LoadingSkeleton } from '@/components'
import { useT } from '@/i18n'
import { useFormatters } from '@/lib/timeZoneContext'
import styles from './Points.module.css'

function CandidateList({
  legend,
  items,
  selected,
  onToggle,
}: {
  legend: string
  items: SimulationCandidate[]
  selected: Set<string>
  onToggle: (taskId: string) => void
}) {
  const t = useT()
  const f = useFormatters()
  return (
    <fieldset className={styles.fieldset}>
      <legend className={styles.subTitle}>{legend}</legend>
      {items.length === 0 ? <p className={styles.muted}>{t('myPoints.sim.none')}</p> : null}
      {items.map((item) => (
        <label key={item.taskId} className={styles.check}>
          <input type="checkbox" checked={selected.has(item.taskId)} onChange={() => onToggle(item.taskId)} />
          <span>
            {item.title} · {t(`status.difficulty.${item.difficulty}`)} · <strong>{f.pointsLabel(item.points)}</strong>
            <span className={styles.muted}> · {t('myPoints.sim.owner', { name: item.owner.name })}</span>
          </span>
        </label>
      ))}
    </fieldset>
  )
}

/** Mô phỏng `mất task này / nhận task kia`: chỉ xem trước (POST chỉ đọc), mọi số do server tính. `?taskId=` chọn sẵn một task. */
export function Simulation() {
  const t = useT()
  const f = useFormatters()
  const { groupId } = useGroup()
  const [params] = useSearchParams()
  const candidates = useSimulationCandidates(groupId, true)
  const simulate = useSimulatePoints(groupId)
  const [selected, setSelected] = useState(() => new Set(params.get('taskId') ? [params.get('taskId') as string] : []))

  const toggle = (taskId: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (!next.delete(taskId)) next.add(taskId)
      return next
    })

  const data = candidates.data
  const outIds = data?.transferable.filter((c) => selected.has(c.taskId)).map((c) => c.taskId) ?? []
  const takeIds = data?.takeable.filter((c) => selected.has(c.taskId)).map((c) => c.taskId) ?? []
  const invalid = isApiError(simulate.error) && simulate.error.code === ErrorCode.SIMULATION_INVALID_TASK
  const result = simulate.data

  const run = () => {
    if (simulate.isPending || outIds.length + takeIds.length === 0) return
    simulate.mutate(
      { transferOutTaskIds: outIds, takeOnTaskIds: takeIds },
      {
        onError: (error) => {
          if (isApiError(error) && error.code === ErrorCode.SIMULATION_INVALID_TASK) {
            setSelected(new Set())
            void candidates.refetch()
          }
        },
      },
    )
  }

  return (
    <Card as="section" aria-labelledby="sim-title" className={styles.block}>
      <h2 id="sim-title" className={styles.blockTitle}>
        {t('myPoints.sim.title')}
      </h2>
      <p className={styles.muted}>{t('myPoints.sim.intro')}</p>
      {candidates.isPending ? <LoadingSkeleton variant="text" lines={3} label={t('states.loading')} /> : null}
      {candidates.isError ? <InlineError error={candidates.error} title={t('myPoints.sim.loadError')} onRetry={() => void candidates.refetch()} /> : null}
      {data ? (
        <>
          <CandidateList legend={t('myPoints.sim.out')} items={data.transferable} selected={selected} onToggle={toggle} />
          <CandidateList legend={t('myPoints.sim.take')} items={data.takeable} selected={selected} onToggle={toggle} />
          {outIds.length + takeIds.length === 0 ? <p className={styles.muted}>{t('myPoints.sim.chooseOne')}</p> : null}
          <div className={styles.row}>
            <Button onClick={run} loading={simulate.isPending} disabled={outIds.length + takeIds.length === 0}>
              {simulate.isPending ? t('myPoints.sim.running') : t('myPoints.sim.run')}
            </Button>
          </div>
        </>
      ) : null}
      {simulate.isError ? (
        invalid ? (
          <InlineError tone="warning" message={t('myPoints.sim.invalid')} />
        ) : (
          <InlineError error={simulate.error} title={t('myPoints.sim.failed')} />
        )
      ) : null}
      <div aria-live="polite">
        {result ? (
          <div className={styles.result}>
            <h3 className={styles.subTitle}>{t('myPoints.sim.result')}</h3>
            <ol className={styles.steps}>
              {result.steps.map((step, index) => (
                <li key={`${step.kind}-${step.taskId ?? index}`}>{step.label}</li>
              ))}
            </ol>
            <p>{t('myPoints.sim.confirmedNow', { points: f.pointsLabel(result.confirmedPoints) })}</p>
            <p>
              <strong>{t('myPoints.sim.end', { points: f.pointsLabel(result.endPotentialPoints) })}</strong>
            </p>
            <p>{result.notice}</p>
          </div>
        ) : null}
      </div>
    </Card>
  )
}
