import { useRef } from 'react'

/**
 * Chặn bấm đúp: `isPending` của mutation chỉ cập nhật sau khi render, nên cú bấm thứ hai (lọt vào giữa lúc validate
 * bất đồng bộ hoặc ngay sau khi phản hồi về, lúc màn hình chưa kịp đổi trạng thái) vẫn gửi lần nữa nếu chỉ dựa vào nó.
 * Khóa giữ nguyên sau khi thành công (form sẽ biến mất/điều hướng); chỉ mở lại khi gửi lỗi để người dùng thử lại.
 * Dùng: `once((release) => mutation.mutate(body, { onError: release }))`.
 */
export function useSingleFlight() {
  const busy = useRef(false)
  return (start: (release: () => void) => void) => {
    if (busy.current) return
    busy.current = true
    start(() => {
      busy.current = false
    })
  }
}
