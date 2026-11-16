import { describe, expect, it } from 'vitest'
import * as Api from '@/api/types'
import { defaultT } from '@/i18n'
import { STATUS_TONES, statusChip, type StatusDomain } from './presets'

/** Mỗi domain ứng với một enum của hợp đồng API: kiểm tra phủ ĐỦ giá trị và có nhãn tiếng Việt. */
const ENUMS: Record<StatusDomain, Record<string, string>> = {
  plan: Api.PlanStatus,
  task: Api.TaskStatus,
  round: Api.RoundState,
  timeliness: Api.TaskTimeliness,
  risk: Api.RiskStatus,
  reportRisk: Api.ReportRiskLevel,
  cyclePhase: Api.CyclePhase,
  checkpoint: Api.CheckpointStatus,
  report: Api.MemberReportState,
  groupHealth: Api.GroupHealth,
  support: Api.SupportRequestStatus,
  help: Api.HelpRequestStatus,
  offer: Api.SupportOfferStatus,
  work: Api.SupportWorkStatus,
  contribution: Api.ContributionStatus,
  pointTask: Api.PointTaskStatus,
  difficulty: Api.Difficulty,
}

describe('statusChip', () => {
  it.each(Object.keys(ENUMS) as StatusDomain[])('domain %s phủ đủ giá trị enum, mỗi giá trị có tông màu và nhãn chữ', (domain) => {
    const values = Object.values(ENUMS[domain])
    expect(Object.keys(STATUS_TONES[domain]).sort()).toEqual([...values].sort())
    for (const value of values) {
      const chip = statusChip(defaultT, domain, value as never)
      expect(chip.label).not.toBe('')
      expect(chip.label).not.toContain('status.') // không rơi về khóa thô
    }
  })

  it('nhãn kỳ checkpoint theo spec 0.6', () => {
    expect(statusChip(defaultT, 'cyclePhase', 'TENTATIVE').label).toBe('Tạm tính')
    expect(statusChip(defaultT, 'cyclePhase', 'AWAITING_LEAD_REVIEW').label).toBe('Chờ TL kiểm tra')
    expect(statusChip(defaultT, 'cyclePhase', 'FINALIZED').label).toBe('Đã chốt đánh giá')
  })
})
