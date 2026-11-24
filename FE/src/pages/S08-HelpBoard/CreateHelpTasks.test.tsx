import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole } from '@/api/types'
import { GROUP_ID, MINH, taskSummary } from '@/test/fixtures'
import { mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

const help = `/groups/${GROUP_ID}/help`
const empty = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 }
const filler = (from: number, count: number) =>
  Array.from({ length: count }, (_, i) => taskSummary({ id: `f-${from + i}`, title: `Filler ${from + i}`, taskStatus: 'IN_PROGRESS', hasOpenHelpRequest: false }))

describe('S08 — chọn task khi đăng yêu cầu (T-03)', () => {
  it('[T-03] nhóm > 100 task: task muộn được chọn sẵn theo id dù nằm ngoài trang đầu; server lọc chưa hoàn thành; có thể tải thêm trang', async () => {
    const api = mockApi([
      ['GET', `/groups/${GROUP_ID}/help-requests`, empty],
      [
        'GET',
        `/groups/${GROUP_ID}/tasks`,
        (call) => {
          const page = Number(call.search.get('page') ?? 0)
          return { items: page === 0 ? filler(0, 100) : filler(100, 30), page, size: 100, totalItems: 130, totalPages: 2 }
        },
      ],
      ['GET', '/tasks/task-late', taskSummary({ id: 'task-late', title: 'Task muộn', taskStatus: 'IN_PROGRESS', hasOpenHelpRequest: false })],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(`${help}?create=1&taskId=task-late`)
    const dialog = await screen.findByRole('dialog')
    await screen.findByRole('option', { name: /Task muộn/ })
    const select = () => dialog.querySelector('select') as HTMLSelectElement // <select> được gắn lại khi số tùy chọn đổi
    await waitFor(() => expect(select().value).toBe('task-late'))
    expect(api.calls.find((c) => c.path === `/groups/${GROUP_ID}/tasks`)?.search.get('completion')).toBe('UNFINISHED')

    // còn trang sau: nút tải thêm gọi đúng page=1 và thêm các task mới vào danh sách chọn
    expect(Array.from(select().options).some((o) => o.text.includes('Filler 120'))).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Tải thêm task' }))
    await waitFor(() => expect(api.calls.some((c) => c.path === `/groups/${GROUP_ID}/tasks` && c.search.get('page') === '1')).toBe(true))
    await waitFor(() => expect(Array.from(select().options).some((o) => o.text.includes('Filler 120'))).toBe(true))
    expect(within(dialog).queryByRole('button', { name: 'Tải thêm task' })).not.toBeInTheDocument()
  })

  it('[T-03] taskId lạ trong URL: form vẫn mở và không chọn nhầm task khác', async () => {
    mockApi([
      ['GET', `/groups/${GROUP_ID}/help-requests`, empty],
      ['GET', `/groups/${GROUP_ID}/tasks`, { items: filler(0, 3), page: 0, size: 100, totalItems: 3, totalPages: 1 }],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    renderApp(`${help}?create=1&taskId=khong-ton-tai`)
    const dialog = await screen.findByRole('dialog')
    await screen.findByRole('option', { name: /Filler 0/ })
    expect((dialog.querySelector('select') as HTMLSelectElement).value).toBe('')
    expect(within(dialog).queryByRole('button', { name: 'Tải thêm task' })).not.toBeInTheDocument()
  })
})
