import { Navigate, useParams } from 'react-router'
import { useSupportWork, useSupportWorkRef } from '@/api/supportWork'
import { NotFoundState } from '@/app/ErrorPages'
import { useGroup } from '@/app/group/groupContext'
import { paths } from '@/app/routes'
import { LoadingSkeleton } from '@/components'
import { useT } from '@/i18n'

/**
 * Solution 2 không có màn "My Support Work" hay màn nghiệm thu riêng: Helper làm việc ở My Tasks/checkpoint, TL duyệt ở
 * khung duyệt chung của task. Link cũ `/help/:id/work|review` (thông báo, đường dẫn đã lưu) chỉ còn chuyển hướng về
 * chi tiết task. Khóa trên URL có thể là id yêu cầu hỗ trợ hoặc id phần việc (`workId` từ thông báo).
 */
export function SupportRedirect() {
  const t = useT()
  const { helpRequestId = '' } = useParams()
  const { groupId } = useGroup()
  const ref = useSupportWorkRef(helpRequestId)
  const request = ref.data?.request ?? null
  const work = useSupportWork(ref.isSuccess && !request ? helpRequestId : null)

  const taskId = request?.task.id ?? work.data?.task.id ?? null
  if (taskId) return <Navigate to={paths.task(groupId, taskId)} replace />
  if (ref.isError || work.isError) return <NotFoundState homeHref={paths.help(groupId)} homeLabel={t('nav.help')} />
  return <LoadingSkeleton variant="list" count={2} label={t('states.loading')} />
}
