import { useFormContext, useWatch } from 'react-hook-form'
import type { CheckpointCycle, Member } from '@/api/types'
import { FormField, Input, Select, Textarea } from '@/components'
import { useT } from '@/i18n'
import { DIFFICULTIES, type PlanFormValues } from './planForm'
import styles from './Tasks.module.css'

/**
 * Bộ trường kế hoạch dùng chung: giao task (S04), sửa kế hoạch/đề xuất thay đổi (S05).
 * Phải nằm trong `<FormProvider>`. Ngày/giờ tính theo múi giờ nhóm; server kiểm tra phạm vi kỳ/deadline nhóm.
 */
export function PlanFormFields({
  cycles,
  members,
  timeZone,
  showAssignee,
}: {
  cycles: CheckpointCycle[]
  members?: Member[]
  timeZone: string
  showAssignee: boolean
}) {
  const t = useT()
  const { register, formState, control } = useFormContext<PlanFormValues>()
  const errors = formState.errors
  const cycleId = useWatch({ control, name: 'cycleId' })
  const cycle = cycles.find((item) => item.id === cycleId)

  return (
    <div className={styles.formFields}>
      <FormField label={t('tasks.create.name')} required error={errors.title?.message}>
        <Input autoComplete="off" {...register('title')} />
      </FormField>

      {showAssignee ? (
        <FormField label={t('tasks.create.assignee')} required error={errors.assigneeId?.message}>
          <Select {...register('assigneeId')}>
            <option value="">{t('tasks.create.assigneePlaceholder')}</option>
            {members?.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.name}
              </option>
            ))}
          </Select>
        </FormField>
      ) : null}

      <FormField label={t('tasks.create.cycle')} required hint={t('tasks.create.cycleHint')} error={errors.cycleId?.message}>
        <Select {...register('cycleId')}>
          <option value="">{t('tasks.create.cyclePlaceholder')}</option>
          {cycles.map((item) => (
            <option key={item.id} value={item.id}>
              {item.label}
            </option>
          ))}
        </Select>
      </FormField>

      <FormField label={t('tasks.create.scope')} required error={errors.description?.message}>
        <Textarea rows={3} {...register('description')} />
      </FormField>
      <FormField label={t('tasks.create.output')} required error={errors.expectedOutput?.message}>
        <Textarea rows={2} {...register('expectedOutput')} />
      </FormField>

      <div className={styles.row2}>
        <FormField label={t('tasks.create.startDate')} required error={errors.startDate?.message}>
          <Input type="date" {...register('startDate')} />
        </FormField>
        <FormField label={t('tasks.create.startTime')} required error={errors.startTime?.message}>
          <Input type="time" {...register('startTime')} />
        </FormField>
      </div>
      <div className={styles.row2}>
        <FormField
          label={t('tasks.create.deadlineDate')}
          required
          hint={t('tasks.create.deadlineHint', { zone: timeZone, cycle: cycle?.label ?? '…' })}
          error={errors.deadlineDate?.message}
        >
          <Input type="date" {...register('deadlineDate')} />
        </FormField>
        <FormField label={t('tasks.create.deadlineTime')} required error={errors.deadlineTime?.message}>
          <Input type="time" {...register('deadlineTime')} />
        </FormField>
      </div>

      <FormField label={t('tasks.create.difficulty')} required error={errors.difficulty?.message}>
        <Select {...register('difficulty')}>
          <option value="">{t('tasks.create.difficultyPlaceholder')}</option>
          {DIFFICULTIES.map((difficulty) => (
            <option key={difficulty} value={difficulty}>
              {t(`status.difficulty.${difficulty}`)}
            </option>
          ))}
        </Select>
      </FormField>
    </div>
  )
}
