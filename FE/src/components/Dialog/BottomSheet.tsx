import { DialogShell, type DialogProps } from './DialogShell'

export interface BottomSheetProps extends DialogProps {
  /** Mobile: phủ toàn màn hình thay vì cao tối đa 92%. */
  fullScreen?: boolean
}

/**
 * BottomSheet: trên mobile trượt từ dưới lên (hoặc toàn màn hình), trên desktop là ngăn kéo bên phải
 * rộng 420px (dùng cho drawer thông báo S13, chi tiết S05/S09). Cùng hành vi Esc/focus trap với Modal.
 */
export function BottomSheet({ fullScreen, ...props }: BottomSheetProps) {
  return <DialogShell variant="sheet" fullScreen={fullScreen} {...props} />
}
