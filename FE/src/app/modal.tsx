import { Suspense, useCallback, type ReactNode } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router'
import { paths } from './routes'

/** Có màn hình trước TRONG ứng dụng để quay lại không (khác với mở link trực tiếp / từ thông báo)? */
function hasPreviousInAppEntry(locationKey: string): boolean {
  const idx = (window.history.state as { idx?: number } | null)?.idx
  return typeof idx === 'number' ? idx > 0 : locationKey !== 'default'
}

/**
 * Hàm đóng cho route modal S06/S07 (spec 1.1): quay về màn gọi nếu có, nếu không (mở link trực tiếp)
 * thì về trang chủ nhóm S03. Dùng cho `onClose` của `<Modal>`/`<BottomSheet>`.
 */
export function useModalClose(fallback?: string): () => void {
  const navigate = useNavigate()
  const location = useLocation()
  const { groupId } = useParams()
  const target = fallback ?? (groupId ? paths.group(groupId) : paths.groups())
  return useCallback(() => {
    if (hasPreviousInAppEntry(location.key)) navigate(-1)
    else navigate(target, { replace: true })
  }, [location.key, navigate, target])
}

/**
 * Bọc route modal: vẽ màn gọi (`background`) phía sau để link mở trực tiếp vẫn có ngữ cảnh,
 * và vẽ modal (`children`, thường là một trang S06/S07 render `<Modal>`) phía trên.
 */
export function ModalBackground({ background, children }: { background?: ReactNode; children: ReactNode }) {
  return (
    <>
      {background ? <Suspense fallback={null}>{background}</Suspense> : null}
      <Suspense fallback={null}>{children}</Suspense>
    </>
  )
}
