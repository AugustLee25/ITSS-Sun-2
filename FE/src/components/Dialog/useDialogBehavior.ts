import { useEffect, useRef, type RefObject } from 'react'

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), summary'

/** Ngăn xếp các hộp thoại đang mở: chỉ hộp trên cùng xử lý Esc/Tab. */
const stack: symbol[] = []
let scrollLocks = 0

function focusables(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (el) => !el.hasAttribute('hidden') && el.getAttribute('aria-hidden') !== 'true',
  )
}

export interface DialogBehaviorOptions {
  open: boolean
  onClose: () => void
  containerRef: RefObject<HTMLElement | null>
  initialFocusRef?: RefObject<HTMLElement | null>
  closeOnEscape?: boolean
}

/**
 * Hành vi hộp thoại theo spec 8.1: đóng bằng Esc, bẫy focus (Tab/Shift+Tab), khóa cuộn nền,
 * trả focus về phần tử đã mở hộp thoại khi đóng.
 */
export function useDialogBehavior({ open, onClose, containerRef, initialFocusRef, closeOnEscape = true }: DialogBehaviorOptions) {
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    if (!open) return
    const container = containerRef.current
    if (!container) return

    const id = Symbol('dialog')
    stack.push(id)
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (scrollLocks++ === 0) document.body.classList.add('scroll-locked')

    const preferred = focusables(container).find((el) => !el.hasAttribute('data-dialog-close'))
    const initial = initialFocusRef?.current ?? preferred ?? container
    initial.focus({ preventScroll: true })

    function onKeyDown(event: KeyboardEvent) {
      if (stack[stack.length - 1] !== id || !container) return
      if (event.key === 'Escape' && closeOnEscape) {
        event.preventDefault()
        event.stopPropagation()
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const items = focusables(container)
      if (items.length === 0) {
        event.preventDefault()
        container.focus()
        return
      }
      const first = items[0]!
      const last = items[items.length - 1]!
      const active = document.activeElement
      if (!container.contains(active)) {
        event.preventDefault()
        first.focus()
      } else if (event.shiftKey && (active === first || active === container)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      const index = stack.indexOf(id)
      if (index >= 0) stack.splice(index, 1)
      if (--scrollLocks === 0) document.body.classList.remove('scroll-locked')
      if (previouslyFocused && document.contains(previouslyFocused)) {
        previouslyFocused.focus({ preventScroll: true })
      } else {
        document.getElementById('main-content')?.focus({ preventScroll: true })
      }
    }
  }, [open, closeOnEscape, containerRef, initialFocusRef])
}
