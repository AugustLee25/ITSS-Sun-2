import { useRef, type KeyboardEvent } from 'react'
import { cx } from '@/lib/cx'
import styles from './Tasks.module.css'

export interface TabBarItem<T extends string> {
  value: T
  label: string
}

/** Thanh tab có bàn phím (mũi tên/Home/End). Panel do trang gọi gắn `id={panelId}` và `aria-labelledby` theo `tabId(value)`. */
export function TabBar<T extends string>({
  items,
  value,
  onChange,
  label,
  panelId,
}: {
  items: TabBarItem<T>[]
  value: T
  onChange: (value: T) => void
  label: string
  panelId: string
}) {
  const refs = useRef(new Map<T, HTMLButtonElement>())

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    let next = -1
    if (event.key === 'ArrowRight') next = (index + 1) % items.length
    else if (event.key === 'ArrowLeft') next = (index - 1 + items.length) % items.length
    else if (event.key === 'Home') next = 0
    else if (event.key === 'End') next = items.length - 1
    const target = items[next]
    if (!target) return
    event.preventDefault()
    onChange(target.value)
    refs.current.get(target.value)?.focus()
  }

  return (
    <div role="tablist" aria-label={label} className={styles.tabs}>
      {items.map((item, index) => (
        <button
          key={item.value}
          type="button"
          role="tab"
          id={`${panelId}-tab-${item.value}`}
          aria-selected={item.value === value}
          aria-controls={panelId}
          tabIndex={item.value === value ? 0 : -1}
          className={cx(styles.tab, item.value === value && styles.tabActive)}
          ref={(element) => {
            if (element) refs.current.set(item.value, element)
            else refs.current.delete(item.value)
          }}
          onClick={() => onChange(item.value)}
          onKeyDown={(event) => onKeyDown(event, index)}
        >
          {item.label}
        </button>
      ))}
    </div>
  )
}
