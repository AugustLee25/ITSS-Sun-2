import { useState, type ReactNode } from 'react'
import { useStartTask } from '@/api/tasks'
import { TaskAction, type SubmitResultResponse, type TaskDetail } from '@/api/types'
import { paths } from '@/app/routes'
import { Button, ButtonLink, Card, ConfirmDialog, Icon, InlineError, Modal, useToast } from '@/components'
import { useT, type MessageKey } from '@/i18n'
import { useGroup } from '@/app/group/groupContext'
import { cheerIndex } from './evidence'
import { ProgressDialog } from './ProgressDialog'
import { SubmitResultDialog } from './SubmitResultDialog'
import styles from './TaskDetail.module.css'

const CHEER_COUNT = 4

/** Lời động viên ngắn sau khi nộp (B14/B16): chỉ ghi nhận nỗ lực, KHÔNG nói "hoàn thành"/"đã cộng điểm" (spec 7.2). */
function SubmittedDialog({ task, response, onClose }: { task: TaskDetail; response: SubmitResultResponse; onClose: () => void }) {
  const t = useT()
  const { submission } = response
  const cheer = cheerIndex(task.id, submission.submissionNo, CHEER_COUNT)
  return (
    <Modal
      open
      size="sm"
      title={t('taskDetail.exec.submittedTitle')}
      onClose={onClose}
      footer={<Button onClick={onClose}>{t('taskDetail.exec.submittedClose')}</Button>}
    >
      <div className={styles.cheer}>
        <span className={styles.cheerIcon} aria-hidden="true">
          <Icon name="award" size={32} />
        </span>
        <p className={styles.cheerText}>{t(`taskDetail.exec.cheer.${cheer}` as MessageKey)}</p>
        <p className={styles.muted}>{submission.isOnTime ? t('taskDetail.exec.submittedOnTime') : t('taskDetail.exec.submittedLate')}</p>
        {response.task.points ? <p className={styles.muted}>{t('taskDetail.exec.submittedPoints')}</p> : null}
      </div>
    </Modal>
  )
}

/** Hành động thực hiện của người phụ trách, dựng từ `allowedActions` do server tính (không suy luận lại ở FE). */
export function ExecutionActions({ task, onCarryover }: { task: TaskDetail; onCarryover: () => void }) {
  const t = useT()
  const toast = useToast()
  const { groupId, timeZone } = useGroup()
  const start = useStartTask(groupId, task.id)
  const [confirmStart, setConfirmStart] = useState(false)
  const [progressOpen, setProgressOpen] = useState(false)
  const [submitOpen, setSubmitOpen] = useState(false)
  const [submitted, setSubmitted] = useState<SubmitResultResponse | null>(null)

  const allowed = new Set(task.allowedActions)
  const buttons: Array<{ key: string; node: ReactNode }> = []

  if (allowed.has(TaskAction.START)) {
    buttons.push({
      key: 'start',
      node: (
        <Button key="start" onClick={() => setConfirmStart(true)}>
          {t('taskDetail.start')}
        </Button>
      ),
    })
  }
  if (allowed.has(TaskAction.SUBMIT_RESULT)) {
    buttons.push({
      key: 'submit',
      node: (
        <Button key="submit" icon="send" onClick={() => setSubmitOpen(true)}>
          {t('taskDetail.submitResult')}
        </Button>
      ),
    })
  }
  if (allowed.has(TaskAction.UPDATE_PROGRESS)) {
    buttons.push({
      key: 'progress',
      node: (
        <Button key="progress" variant="secondary" onClick={() => setProgressOpen(true)}>
          {t('taskDetail.updateProgress')}
        </Button>
      ),
    })
  }
  if (allowed.has(TaskAction.REPORT_CHECKPOINT)) {
    buttons.push({
      key: 'checkpoint',
      node: (
        <ButtonLink key="checkpoint" variant="secondary" icon="clock" to={paths.taskCheckpoint(groupId, task.id)} preventScrollReset>
          {t('taskDetail.reportCheckpoint')}
        </ButtonLink>
      ),
    })
  }
  if (allowed.has(TaskAction.REPORT_DIFFICULTY)) {
    buttons.push({
      key: 'difficulty',
      node: (
        <ButtonLink key="difficulty" variant="secondary" to={paths.taskDifficulty(groupId, task.id)} preventScrollReset>
          {t('taskDetail.reportDifficulty')}
        </ButtonLink>
      ),
    })
  }
  if (allowed.has(TaskAction.SET_CARRYOVER)) {
    buttons.push({
      key: 'carryover',
      node: (
        <Button key="carryover" icon="calendar" onClick={onCarryover}>
          {t('taskDetail.carryover.cta')}
        </Button>
      ),
    })
  }
  if (allowed.has(TaskAction.VIEW_RESULT)) {
    buttons.push({
      key: 'view',
      node: (
        <ButtonLink key="view" variant="ghost" to="#results" replace>
          {t('taskDetail.viewResult')}
        </ButtonLink>
      ),
    })
  }

  return (
    <>
      {buttons.length > 0 ? (
        <Card as="section" aria-labelledby="actions-title" className={styles.block}>
          <h2 id="actions-title" className={styles.blockTitle}>
            {t('taskDetail.actionsTitle')}
          </h2>
          <div className={styles.buttonRow}>{buttons.map((b) => b.node)}</div>
          {start.isError ? <InlineError error={start.error} title={t('taskDetail.exec.startFailed')} /> : null}
        </Card>
      ) : null}

      <ConfirmDialog
        open={confirmStart}
        title={t('taskDetail.exec.startTitle')}
        description={t('taskDetail.exec.startDescription')}
        confirmLabel={t('taskDetail.exec.startConfirm')}
        loading={start.isPending}
        onCancel={() => setConfirmStart(false)}
        onConfirm={() =>
          start.mutate(
            { version: task.version },
            {
              onSuccess: () => {
                setConfirmStart(false)
                toast.success(t('taskDetail.exec.started'))
              },
              onError: () => setConfirmStart(false),
            },
          )
        }
      />
      {progressOpen ? <ProgressDialog task={task} groupId={groupId} timeZone={timeZone} onClose={() => setProgressOpen(false)} /> : null}
      {submitOpen ? (
        <SubmitResultDialog task={task} groupId={groupId} onClose={() => setSubmitOpen(false)} onSubmitted={(response) => setSubmitted(response)} />
      ) : null}
      {submitted ? <SubmittedDialog task={task} response={submitted} onClose={() => setSubmitted(null)} /> : null}
    </>
  )
}
