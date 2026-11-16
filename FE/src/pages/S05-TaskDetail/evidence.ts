import { z } from 'zod'
import { EvidenceKind, type EvidenceItem } from '@/api/types'
import type { TFunction } from '@/i18n'

export const MAX_EVIDENCE = 5

export interface EvidenceValues {
  resultNote: string
  evidence: Array<{ url: string; label: string }>
}

/** Minh chứng chỉ là URL https (hợp đồng EvidenceItem: ≤ 2048 ký tự); 1–5 mục. */
export function isHttpsUrl(value: string): boolean {
  if (value.length > 2048) return false
  try {
    return new URL(value).protocol === 'https:'
  } catch {
    return false
  }
}

export function buildEvidenceSchema(t: TFunction) {
  return z.object({
    resultNote: z.string().max(2000, t('taskDetail.exec.errResultNote')),
    evidence: z
      .array(
        z.object({
          url: z.string().trim().refine(isHttpsUrl, t('taskDetail.exec.errEvidenceUrl')),
          label: z.string().trim().max(200, t('taskDetail.exec.errEvidenceLabel')),
        }),
      )
      .min(1, t('taskDetail.exec.errEvidenceUrl'))
      .max(MAX_EVIDENCE, t('taskDetail.exec.evidenceMax')),
  })
}

export function toEvidenceItems(values: EvidenceValues): EvidenceItem[] {
  return values.evidence.map((item) => ({
    type: EvidenceKind.URL,
    url: item.url.trim(),
    ...(item.label.trim() ? { label: item.label.trim() } : {}),
  }))
}

/** Lời động viên sau khi nộp (từ phản hồi phỏng vấn B14/B16): chọn ổn định theo task + lần nộp, không ngẫu nhiên. */
export function cheerIndex(taskId: string, submissionNo: number, count: number): number {
  let hash = submissionNo
  for (const ch of taskId) hash = (hash * 31 + (ch.codePointAt(0) ?? 0)) % 9973
  return hash % count
}
