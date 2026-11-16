import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useT } from '@/i18n'
import { cx } from '@/lib/cx'
import { Icon, type IconName } from '../Icon/Icon'
import { ToastContext, type ToastApi, type ToastOptions, type ToastTone } from './toastContext'
import styles from './Toast.module.css'

interface ToastItem extends Required<Pick<ToastOptions, 'message'>> {
  id: string
  tone: ToastTone
  title?: string
  duration: number
}

const MAX_VISIBLE = 3
const TONE_ICON: Record<ToastTone, IconName> = { success: 'check', error: 'x-circle', info: 'info', warning: 'alert' }

let counter = 0

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const dismiss = useCallback((id: string) => setItems((prev) => prev.filter((item) => item.id !== id)), [])

  const show = useCallback((options: ToastOptions) => {
    const tone = options.tone ?? 'info'
    const id = `toast-${++counter}`
    const item: ToastItem = {
      id,
      tone,
      title: options.title,
      message: options.message,
      duration: options.duration ?? (tone === 'error' ? 8000 : 5000),
    }
    setItems((prev) => [...prev, item].slice(-MAX_VISIBLE))
    return id
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      show,
      dismiss,
      success: (message, title) => show({ tone: 'success', message, title }),
      error: (message, title) => show({ tone: 'error', message, title }),
      info: (message, title) => show({ tone: 'info', message, title }),
      warning: (message, title) => show({ tone: 'warning', message, title }),
    }),
    [show, dismiss],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      {createPortal(
        <div className={styles.viewport}>
          {items.map((item) => (
            <ToastView key={item.id} item={item} onDismiss={dismiss} />
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  )
}

function ToastView({ item, onDismiss }: { item: ToastItem; onDismiss: (id: string) => void }) {
  const t = useT()
  const [paused, setPaused] = useState(false)
  const remaining = useRef(item.duration)

  useEffect(() => {
    if (item.duration === 0 || paused) return
    const startedAt = Date.now()
    const timer = window.setTimeout(() => onDismiss(item.id), remaining.current)
    return () => {
      window.clearTimeout(timer)
      remaining.current = Math.max(1000, remaining.current - (Date.now() - startedAt))
    }
  }, [item.id, item.duration, paused, onDismiss])

  return (
    <div
      role={item.tone === 'error' ? 'alert' : 'status'}
      className={cx(styles.toast, styles[item.tone])}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <Icon name={TONE_ICON[item.tone]} size={20} className={styles.icon} />
      <div className={styles.text}>
        {item.title ? <p className={styles.title}>{item.title}</p> : null}
        <p>{item.message}</p>
      </div>
      <button type="button" className={styles.dismiss} onClick={() => onDismiss(item.id)} aria-label={t('a11y.dismissToast')}>
        <Icon name="x" size={18} />
      </button>
    </div>
  )
}
