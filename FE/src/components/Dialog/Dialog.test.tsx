import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { BottomSheet } from './BottomSheet'
import { ConfirmDialog } from './ConfirmDialog'
import { Modal } from './Modal'

describe('BottomSheet', () => {
  it('đóng bằng phím Esc', async () => {
    const onClose = vi.fn()
    render(
      <BottomSheet open onClose={onClose} title="Thông báo">
        <button>Hành động</button>
      </BottomSheet>,
    )
    expect(screen.getByRole('dialog', { name: 'Thông báo' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('đóng bằng nút X (có nhãn) và khi nhấp nền', async () => {
    const onClose = vi.fn()
    render(
      <BottomSheet open onClose={onClose} title="Chi tiết">
        <p>Nội dung</p>
      </BottomSheet>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Đóng hộp thoại' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    const overlay = screen.getByRole('dialog').parentElement as HTMLElement
    await userEvent.click(overlay)
    expect(onClose).toHaveBeenCalledTimes(2)
  })
})

describe('Modal', () => {
  function Harness() {
    const [open, setOpen] = useState(false)
    return (
      <>
        <button onClick={() => setOpen(true)}>Mở</button>
        <Modal open={open} onClose={() => setOpen(false)} title="Cập nhật" footer={<button>Gửi</button>}>
          <input aria-label="Tình trạng" />
        </Modal>
      </>
    )
  }

  it('đưa focus vào hộp thoại, bẫy Tab, trả focus về nút mở khi đóng', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const opener = screen.getByRole('button', { name: 'Mở' })
    await user.click(opener)
    const input = screen.getByLabelText('Tình trạng')
    expect(input).toHaveFocus()

    await user.tab() // Gửi
    expect(screen.getByRole('button', { name: 'Gửi' })).toHaveFocus()
    await user.tab() // vòng về nút X (phần tử focus được đầu tiên)
    expect(screen.getByRole('button', { name: 'Đóng hộp thoại' })).toHaveFocus()
    await user.tab() // tiếp tục vòng
    expect(input).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('khóa cuộn nền khi mở và mở lại khi đóng', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'Mở' }))
    expect(document.body).toHaveClass('scroll-locked')
    await user.keyboard('{Escape}')
    expect(document.body).not.toHaveClass('scroll-locked')
  })
})

describe('ConfirmDialog', () => {
  it('là alertdialog, tone danger focus vào nút Hủy, xác nhận gọi onConfirm', async () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<ConfirmDialog open tone="danger" title="Rời đi?" confirmLabel="Rời đi" onConfirm={onConfirm} onCancel={onCancel} />)
    expect(screen.getByRole('alertdialog', { name: 'Rời đi?' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hủy' })).toHaveFocus()
    await userEvent.click(screen.getByRole('button', { name: 'Rời đi' }))
    expect(onConfirm).toHaveBeenCalled()
    await userEvent.keyboard('{Escape}')
    expect(onCancel).toHaveBeenCalled()
  })

  it('đang loading thì không cho xác nhận lặp', async () => {
    const onConfirm = vi.fn()
    render(<ConfirmDialog open loading title="Xác nhận" confirmLabel="Gửi" onConfirm={onConfirm} onCancel={() => undefined} />)
    await userEvent.click(screen.getByRole('button', { name: 'Gửi' }))
    expect(onConfirm).not.toHaveBeenCalled()
  })
})
