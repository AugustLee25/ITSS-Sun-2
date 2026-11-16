import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StatusChip } from './StatusChip'
import type { StatusTone } from './tones'

describe('StatusChip', () => {
  it('luôn hiển thị nhãn chữ (không chỉ dựa vào màu)', () => {
    render(<StatusChip tone="warning" label="Trễ 2 task" />)
    expect(screen.getByText('Trễ 2 task')).toBeVisible()
  })

  it('có biểu tượng trang trí ẩn khỏi screen reader, nhãn chữ là nội dung đọc được', () => {
    const { container } = render(<StatusChip tone="danger" label="Cần TL xử lý" />)
    const svg = container.querySelector('svg')
    expect(svg).toHaveAttribute('aria-hidden', 'true')
    expect(container).toHaveTextContent('Cần TL xử lý')
  })

  it.each<StatusTone>(['neutral', 'info', 'success', 'warning', 'danger', 'pending', 'muted'])(
    'tone %s có biểu tượng mặc định và có thể tắt bằng icon={false}',
    (tone) => {
      const { container, rerender } = render(<StatusChip tone={tone} label="Nhãn" />)
      expect(container.querySelector('svg')).not.toBeNull()
      rerender(<StatusChip tone={tone} label="Nhãn" icon={false} />)
      expect(container.querySelector('svg')).toBeNull()
      expect(screen.getByText('Nhãn')).toBeInTheDocument()
    },
  )
})
