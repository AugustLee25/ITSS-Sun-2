import { useId, useRef, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { Icon } from '../Icon/Icon'
import { useDialogBehavior } from './useDialogBehavior'
import styles from './Dialog.module.css'

export interface DialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  children?: ReactNode
  /** Vùng nút hành động cố định phía dưới (trên mobile luôn nằm trong vùng chạm, không che nội dung). */
  footer?: ReactNode
  /** Ẩn tiêu đề trực quan nhưng vẫn gắn aria-labelledby. */
  hideTitle?: boolean
  closeOnBackdrop?: boolean
  closeOnEscape?: boolean
  initialFocusRef?: RefObject<HTMLElement | null>
  /** `alertdialog` cho xác nhận hành động nguy hiểm. */
  role?: 'dialog' | 'alertdialog'
  closeLabel?: string
  className?: string
}

interface ShellProps extends DialogProps {
  variant: 'modal' | 'sheet'
  size?: 'sm' | 'md' | 'lg'
  fullScreen?: boolean
}

/** Khung dùng chung của Modal / BottomSheet (nội bộ). */
export function DialogShell({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  hideTitle,
  closeOnBackdrop = true,
  closeOnEscape = true,
  initialFocusRef,
  role = 'dialog',
  closeLabel,
  className,
  variant,
  size = 'md',
  fullScreen,
}: ShellProps) {
  const t = useT()
  const titleId = useId()
  const descId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const pressedOnBackdrop = useRef(false)
  useDialogBehavior({ open, onClose, containerRef: panelRef, initialFocusRef, closeOnEscape })

  if (!open) return null

  return createPortal(
    <div
      className={cx(styles.overlay, variant === 'modal' ? styles.modal : styles.sheet, fullScreen && styles.fullScreen)}
      onMouseDown={(event) => {
        pressedOnBackdrop.current = event.target === event.currentTarget
      }}
      onClick={(event) => {
        if (closeOnBackdrop && pressedOnBackdrop.current && event.target === event.currentTarget) onClose()
        pressedOnBackdrop.current = false
      }}
    >
      <div
        ref={panelRef}
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className={cx(styles.panel, variant === 'modal' && styles[size], className)}
      >
        {variant === 'sheet' ? <span className={styles.handle} aria-hidden="true" /> : null}
        <div className={styles.header}>
          <h2 id={titleId} className={cx(styles.title, hideTitle && 'visually-hidden')}>
            {title}
          </h2>
          <button type="button" className={styles.close} data-dialog-close="" onClick={onClose} aria-label={closeLabel ?? t('a11y.closeDialog')}>
            <Icon name="x" size={22} />
          </button>
        </div>
        <div className={styles.body}>
          {description ? (
            <p id={descId} className={styles.description}>
              {description}
            </p>
          ) : null}
          {children}
        </div>
        {footer ? <div className={styles.footer}>{footer}</div> : null}
      </div>
    </div>,
    document.body,
  )
}
