import { DialogShell, type DialogProps } from './DialogShell'

export interface ModalProps extends DialogProps {
  size?: 'sm' | 'md' | 'lg'
}

/**
 * Modal: hộp thoại giữa màn hình trên desktop/tablet, TOÀN MÀN HÌNH trên mobile (<768px).
 * Đóng bằng Esc, nút X, nhấp nền; bẫy focus; trả focus về nút đã mở. Dùng làm vỏ cho S06/S07.
 */
export function Modal({ size = 'md', ...props }: ModalProps) {
  return <DialogShell variant="modal" size={size} {...props} />
}
