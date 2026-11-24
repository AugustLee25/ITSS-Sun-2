import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GroupRole, type MemberTaskBoardRow } from '@/api/types'
import { BINH, GROUP_ID, MINH, boardRow, cycle, dashboard, memberBoard } from '@/test/fixtures'
import { mockApi, renderApp, shellRoutes } from '@/test/renderApp'

afterEach(() => vi.unstubAllGlobals())

describe('S03 — bảng task theo Member: dữ liệu bất thường từ server', () => {
  it('[T-06] section lạ (BE thêm sau này) hiện nhãn "Khác", không lộ khóa i18n thô', async () => {
    const unknown = { ...boardRow('ACTIVE', { id: 't-foo', title: 'Task nhóm lạ' }), section: 'FOO' } as unknown as MemberTaskBoardRow
    mockApi([
      ['GET', `/groups/${GROUP_ID}/dashboard`, dashboard(GroupRole.LEAD)],
      ['GET', `/groups/${GROUP_ID}/dashboard/members/${BINH.userId}`, { cycle: cycle(3), progress: dashboard().memberProgress[1], tasks: [] }],
      ['GET', `/groups/${GROUP_ID}/dashboard/members/${BINH.userId}/tasks`, memberBoard([boardRow('OVERDUE', { id: 't-over', title: 'Task quá hạn' }), unknown])],
      ...shellRoutes(MINH, GroupRole.LEAD),
    ])
    const user = userEvent.setup()
    renderApp(`/groups/${GROUP_ID}`)
    const members = (await screen.findByRole('heading', { name: 'Tiến độ thành viên' })).closest('section') as HTMLElement
    await user.click(within(members).getByRole('button', { name: /Bình/ }))
    const sheet = await screen.findByRole('dialog', { name: 'Task của Bình' })
    await within(sheet).findByTestId('member-task-board')
    expect(within(sheet).getByRole('heading', { name: 'Khác (1)' })).toBeInTheDocument()
    expect(sheet.textContent).not.toMatch(/overview\.board\.sections/)
    expect(within(sheet).getByRole('link', { name: 'Task nhóm lạ' })).toBeInTheDocument()
  })
})
