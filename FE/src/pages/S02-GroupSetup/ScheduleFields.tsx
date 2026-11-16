import type { UseQueryResult } from '@tanstack/react-query'
import type { SchedulePreviewResponse } from '@/api/types'
import { FormField, InlineError, Input, LoadingSkeleton, Select, StatusChip } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { formatDateTime } from '@/lib/format'
import { COMMON_TIMEZONES, WEEKDAYS, WINDOW_OPTIONS, type ScheduleValues } from './scheduleForm'
import styles from './GroupSetup.module.css'

export interface ScheduleFieldsProps {
  values: ScheduleValues
  onChange: (patch: Partial<ScheduleValues>) => void
  errors?: Partial<Record<keyof ScheduleValues, string>>
}

/** Bộ trường lịch checkpoint dùng chung cho wizard tạo nhóm và cài đặt nhóm (spec S02 bước 2). */
export function ScheduleFields({ values, onChange, errors = {} }: ScheduleFieldsProps) {
  const t = useT()
  const timezones = (COMMON_TIMEZONES as readonly string[]).includes(values.timezone)
    ? [...COMMON_TIMEZONES]
    : [values.timezone, ...COMMON_TIMEZONES]
  return (
    <div className={styles.fields}>
      <div className={styles.row2}>
        <FormField label={t('groupSetup.weekday')} required error={errors.weekday}>
          <Select value={values.weekday} onChange={(event) => onChange({ weekday: event.target.value })}>
            {WEEKDAYS.map((day) => (
              <option key={day} value={day}>
                {t(`groupSetup.weekdays.${day}` as MessageKey)}
              </option>
            ))}
          </Select>
        </FormField>
        <FormField label={t('groupSetup.cutoffTime')} required hint={t('groupSetup.cutoffHint')} error={errors.cutoffTime}>
          <Input type="time" value={values.cutoffTime} onChange={(event) => onChange({ cutoffTime: event.target.value })} />
        </FormField>
      </div>
      <FormField label={t('groupSetup.timezone')} required error={errors.timezone}>
        <Select value={values.timezone} onChange={(event) => onChange({ timezone: event.target.value })}>
          {timezones.map((zone) => (
            <option key={zone} value={zone}>
              {(COMMON_TIMEZONES as readonly string[]).includes(zone) ? t(`groupSetup.commonTimezones.${zone}` as MessageKey) : zone}
            </option>
          ))}
        </Select>
      </FormField>
      <FormField label={t('groupSetup.window')} hint={t('groupSetup.windowHint')} error={errors.window}>
        <Select value={values.window} onChange={(event) => onChange({ window: event.target.value })}>
          <option value="">{t('groupSetup.windowFull')}</option>
          {WINDOW_OPTIONS.map((minutes) => (
            <option key={minutes} value={minutes}>
              {t('groupSetup.windowMinutes', { label: t(`groupSetup.windowLabels.${minutes}` as MessageKey) })}
            </option>
          ))}
        </Select>
      </FormField>
    </div>
  )
}

/** Xem trước do SERVER dựng (`POST /checkpoint-schedules/preview`): FE chỉ hiển thị, không tự cộng trừ giờ. */
export function SchedulePreviewPanel({
  query,
  timeZone,
}: {
  query: UseQueryResult<SchedulePreviewResponse>
  timeZone: string
}) {
  const t = useT()
  const data = query.data
  return (
    <section className={styles.preview} aria-labelledby="schedule-preview-title" aria-live="polite">
      <h3 id="schedule-preview-title" className={styles.previewTitle}>
        {t('groupSetup.previewTitle')}
      </h3>
      {query.isError && !data ? (
        <InlineError error={query.error} title={t('groupSetup.previewError')} onRetry={() => void query.refetch()} />
      ) : !data ? (
        query.isFetching ? (
          <LoadingSkeleton variant="text" lines={3} label={t('groupSetup.previewLoading')} />
        ) : (
          <p className={styles.muted}>{t('groupSetup.previewIdle')}</p>
        )
      ) : (
        <>
          <p className={styles.previewSummary}>
            <strong>{data.summaryLabel}</strong>
            <span className={styles.muted}> · {t('groupSetup.previewReminder', { hours: data.reminderBeforeMinutes / 60 })}</span>
          </p>
          {data.warnings.length > 0 ? (
            <ul className={styles.warnings}>
              {data.warnings.map((warning) => (
                <li key={`${warning.code}-${warning.message}`}>
                  <InlineError tone="warning" message={warning.message} />
                </li>
              ))}
            </ul>
          ) : null}
          <p className={styles.muted}>{t('groupSetup.previewCycles')}</p>
          <ol className={styles.cycles}>
            {data.cycles.map((cycle) => (
              <li key={cycle.sequenceNo} className={styles.cycle}>
                <span className={styles.cycleLabel}>{cycle.label}</span>
                <span className={styles.muted}>
                  {t('groupSetup.previewRemindAt', { time: formatDateTime(cycle.reminderAt, timeZone) })}
                </span>
                {cycle.isTransition ? (
                  <StatusChip
                    tone="warning"
                    size="sm"
                    label={`${t('groupSetup.previewTransition')} · ${t('groupSetup.previewHours', { hours: cycle.periodHours })}`}
                  />
                ) : null}
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  )
}
