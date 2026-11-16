import { forwardRef, type ButtonHTMLAttributes, type MouseEvent } from 'react'
import { Link, type LinkProps } from 'react-router'
import { Icon, type IconName } from '../Icon/Icon'
import styles from './Button.module.css'
import { buttonClassName, type ButtonSize, type ButtonVariant } from './buttonClassName'

interface StyleProps {
  variant?: ButtonVariant
  size?: ButtonSize
  fullWidth?: boolean
  icon?: IconName
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, StyleProps {
  /** Đang gửi: hiện spinner, chặn bấm lặp (spec 2.4 #1). Dùng aria-disabled để giữ focus. */
  loading?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant, size, fullWidth, icon, loading, className, children, onClick, type = 'button', disabled, ...rest },
  ref,
) {
  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    if (loading) {
      event.preventDefault()
      return
    }
    onClick?.(event)
  }
  return (
    <button
      ref={ref}
      type={type}
      className={buttonClassName({ variant, size, fullWidth }, className)}
      disabled={disabled}
      aria-disabled={loading || undefined}
      aria-busy={loading || undefined}
      onClick={handleClick}
      {...rest}
    >
      {loading ? <span className={styles.spinner} aria-hidden="true" /> : icon ? <Icon name={icon} size={18} /> : null}
      <span>{children}</span>
    </button>
  )
})

export interface ButtonLinkProps extends LinkProps, StyleProps {}

/** Liên kết nội bộ có giao diện nút. */
export function ButtonLink({ variant, size, fullWidth, icon, className, children, ...rest }: ButtonLinkProps) {
  return (
    <Link className={buttonClassName({ variant, size, fullWidth }, className)} {...rest}>
      {icon ? <Icon name={icon} size={18} /> : null}
      <span>{children}</span>
    </Link>
  )
}
