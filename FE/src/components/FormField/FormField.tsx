import {
  cloneElement,
  forwardRef,
  isValidElement,
  useId,
  type InputHTMLAttributes,
  type ReactElement,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react'
import { cx } from '@/lib/cx'
import { useT } from '@/i18n'
import { Icon } from '../Icon/Icon'
import styles from './FormField.module.css'

/** Thuộc tính a11y mà FormField truyền cho control bên trong. */
export interface FieldControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
  'aria-required'?: true
}

export interface FormFieldProps {
  /** Nhãn luôn hiển thị (placeholder không thay thế label — spec 8.1). */
  label: ReactNode
  hint?: ReactNode
  /** Thông báo lỗi của trường (gắn aria-describedby + aria-invalid). */
  error?: string
  required?: boolean
  className?: string
  /** Control là phần tử (được clone thêm id/aria) hoặc hàm nhận thuộc tính a11y. */
  children: ReactElement<Partial<FieldControlProps>> | ((props: FieldControlProps) => ReactNode)
}

export function FormField({ label, hint, error, required, className, children }: FormFieldProps) {
  const t = useT()
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined
  const controlProps: FieldControlProps = {
    id,
    'aria-describedby': describedBy,
    'aria-invalid': error ? true : undefined,
    'aria-required': required ? true : undefined,
  }
  const control =
    typeof children === 'function' ? children(controlProps) : isValidElement(children) ? cloneElement(children, controlProps) : children

  return (
    <div className={cx(styles.field, className)}>
      <label htmlFor={id} className={styles.label}>
        {label}
        {required ? (
          <>
            <span aria-hidden="true" className={styles.star}>
              {' '}
              *
            </span>
            <span className="visually-hidden"> ({t('common.required')})</span>
          </>
        ) : null}
      </label>
      {hint ? (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      ) : null}
      {control}
      {error ? (
        <p id={errorId} className={styles.error}>
          <Icon name="alert" size={16} />
          <span>{error}</span>
        </p>
      ) : null}
    </div>
  )
}

export interface ControlProps {
  invalid?: boolean
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & ControlProps>(function Input(
  { invalid, className, ...rest },
  ref,
) {
  return (
    <input
      ref={ref}
      className={cx(styles.control, invalid && styles.invalid, className)}
      {...rest}
      aria-invalid={invalid || rest['aria-invalid'] || undefined}
    />
  )
})

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement> & ControlProps>(
  function Textarea({ invalid, className, rows = 4, ...rest }, ref) {
    return (
      <textarea
        ref={ref}
        rows={rows}
        className={cx(styles.control, styles.textarea, invalid && styles.invalid, className)}
        {...rest}
        aria-invalid={invalid || rest['aria-invalid'] || undefined}
      />
    )
  },
)

export const Select = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement> & ControlProps>(function Select(
  { invalid, className, children, ...rest },
  ref,
) {
  return (
    <select
      ref={ref}
      className={cx(styles.control, styles.select, invalid && styles.invalid, className)}
      {...rest}
      aria-invalid={invalid || rest['aria-invalid'] || undefined}
    >
      {children}
    </select>
  )
})
