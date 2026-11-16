import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ToastProvider } from '../Toast/ToastProvider'
import { useToast } from '../Toast/toastContext'
import { FormField, Input } from './FormField'

describe('FormField', () => {
  it('label luôn hiển thị, gắn với control; lỗi gắn aria-describedby + aria-invalid', () => {
    render(
      <FormField label="Tên công việc" required hint="Ví dụ: Thiết kế" error="Vui lòng nhập tên công việc">
        <Input />
      </FormField>,
    )
    const input = screen.getByLabelText(/Tên công việc/)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-required', 'true')
    expect(input).toHaveAccessibleDescription('Ví dụ: Thiết kế Vui lòng nhập tên công việc')
  })

  it('không có lỗi thì không đánh dấu invalid', () => {
    render(
      <FormField label="Ghi chú">
        <Input />
      </FormField>,
    )
    expect(screen.getByLabelText('Ghi chú')).not.toHaveAttribute('aria-invalid')
  })
})

describe('Toast', () => {
  function Trigger() {
    const toast = useToast()
    return (
      <>
        <button onClick={() => toast.success('Đã lưu')}>ok</button>
        <button onClick={() => toast.error('Không gửi được')}>fail</button>
      </>
    )
  }

  it('thành công dùng role=status, lỗi dùng role=alert, ẩn được bằng nút', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    )
    await user.click(screen.getByRole('button', { name: 'ok' }))
    expect(screen.getByRole('status')).toHaveTextContent('Đã lưu')
    await user.click(screen.getByRole('button', { name: 'fail' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Không gửi được')
    await user.click(screen.getAllByRole('button', { name: 'Ẩn thông báo' })[0]!)
    expect(screen.queryByText('Đã lưu')).not.toBeInTheDocument()
  })
})
