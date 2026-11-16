/* Trang kiểm tra bằng mắt cho design system — chỉ có khi `import.meta.env.DEV` (route /__kit). Chuỗi demo viết thẳng, không đưa vào i18n. */
import { useState, type ReactNode } from 'react'
import {
  AppShell,
  BottomSheet,
  Button,
  ButtonLink,
  Card,
  CheckpointPrompt,
  ConfirmDialog,
  ContributionCard,
  EmptyState,
  FormField,
  GroupSwitcher,
  HelpRequestCard,
  InlineError,
  Input,
  LoadingSkeleton,
  MemberAvatar,
  MemberStatusFrame,
  Modal,
  NotificationItem,
  PageHeader,
  Select,
  StatusChip,
  TaskCard,
  Textarea,
  Timeline,
  TimelineEntry,
  STATUS_TONES,
  statusChip,
  useToast,
  type NavItem,
  type StatusTone,
} from '@/components'
import { ApiError } from '@/api/errors'
import { NotificationPriority, NotificationType } from '@/api/types'
import { useT } from '@/i18n'
import { formatDateTime, formatPointsLabel } from '@/lib/format'
import { TimeZoneContext } from '@/lib/timeZoneContext'
import styles from './KitPage.module.css'

const NOTIFICATION_TYPES = Object.values(NotificationType)
const amount = (units: number) => ({ pointUnits: units, percentLabel: (units / 10_000).toFixed(4).replace('.', ',') })
const TONES: StatusTone[] = ['neutral', 'info', 'success', 'warning', 'danger', 'pending', 'muted']
const NOW = new Date().toISOString()
const HOURS_AGO = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString()
const IN_HOURS = (h: number) => new Date(Date.now() + h * 3_600_000).toISOString()

const DEMO_NAV: NavItem[] = [
  { key: 'o', label: 'Tổng quan', to: '/__kit', icon: 'overview', end: true },
  { key: 't', label: 'Công việc', to: '/groups/demo/tasks', icon: 'tasks' },
  { key: 'h', label: 'Hỗ trợ', to: '/groups/demo/help', icon: 'help' },
  { key: 'p', label: 'Điểm & Ghi nhận', to: '/groups/demo/points', icon: 'award' },
]

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={styles.section}>
      <h2>{title}</h2>
      <div className={styles.stack}>{children}</div>
    </section>
  )
}

export default function KitPage() {
  const t = useT()
  const toast = useToast()
  const [modal, setModal] = useState(false)
  const [sheet, setSheet] = useState<'none' | 'normal' | 'full'>('none')
  const [confirm, setConfirm] = useState(false)
  const [loading, setLoading] = useState(false)
  const [withShell, setWithShell] = useState(true)

  const body = (
    <>
      <PageHeader
        title="Bộ kiểm tra giao diện (/__kit)"
        breadcrumbs={[{ label: 'Công việc', to: '/groups/demo/tasks' }, { label: 'Tên task mẫu' }]}
        status={<StatusChip tone="info" label="Chỉ DEV" />}
        actions={
          <Button variant="secondary" onClick={() => setWithShell((v) => !v)}>
            {withShell ? 'Ẩn khung AppShell' : 'Hiện khung AppShell'}
          </Button>
        }
        description="Thu hẹp cửa sổ <768px để xem bottom nav và modal toàn màn hình; 768–1023px để xem sidebar thu gọn. Các link điều hướng demo trỏ tới /groups/demo (cần đăng nhập)."
      />

      <Section title="StatusChip — nhãn chữ + biểu tượng, không phụ thuộc màu">
        <div className={styles.row}>
          {TONES.map((tone) => (
            <StatusChip key={tone} tone={tone} label={t(`a11y.tone.${tone}`)} />
          ))}
        </div>
        <div className={styles.row}>
          <StatusChip tone="success" size="sm" label="Đã xác nhận hoàn thành" />
          <StatusChip tone="pending" size="sm" label="Chờ TL duyệt" />
          <StatusChip tone="warning" size="sm" label="Trễ 2 task" />
          <StatusChip tone="danger" size="sm" label="Cần TL xử lý" />
          <StatusChip tone="neutral" size="sm" icon="clock" label={t('status.cyclePhase.TENTATIVE')} />
        </div>
      </Section>

      <Section title="Nhãn trạng thái theo enum API (useStatusChip / STATUS_TONES)">
        {(Object.keys(STATUS_TONES) as Array<keyof typeof STATUS_TONES>).map((domain) => (
          <div key={domain} className={styles.row}>
            <code>{domain}</code>
            {Object.keys(STATUS_TONES[domain]).map((value) => (
              <StatusChip key={value} size="sm" {...statusChip(t, domain, value as never)} />
            ))}
          </div>
        ))}
      </Section>

      <Section title="MemberAvatar / MemberStatusFrame (spec 0.6)">
        <div className={styles.row}>
          <MemberAvatar name="Nguyễn Văn An" size="sm" />
          <MemberAvatar name="Trần Thị Bình" />
          <MemberAvatar name="Lê Hoàng" size="lg" />
          <MemberAvatar name="Phạm Dũng" />
        </div>
        <div className={styles.grid}>
          <MemberStatusFrame name="Nguyễn Văn An" color="GREEN" reason="ALL_ACCEPTED" detail="3/3 task đã duyệt" isSelf />
          <MemberStatusFrame name="Trần Thị Bình" color="GREEN" reason="ALL_SUBMITTED_PENDING_REVIEW" provisional pendingCount={2} onClick={() => toast.info('Mở danh sách task của Bình')} />
          <MemberStatusFrame name="Lê Hoàng" color="YELLOW" label="Trễ 2 task" detail="Hạn gần nhất: 15/11/2026, 17:00" />
          <MemberStatusFrame name="Phạm Dũng" color="RED" reason="LATE_3_PLUS" detail="Có lý do và người xác nhận" />
          <MemberStatusFrame name="Hoàng Mai" color="GRAY" reason="NO_TASK_DUE" detail="Chưa có cam kết trong kỳ" />
        </div>
      </Section>

      <Section title="Button">
        <div className={styles.row}>
          <Button>Chính</Button>
          <Button variant="secondary">Phụ</Button>
          <Button variant="ghost">Nhẹ</Button>
          <Button variant="danger">Nguy hiểm</Button>
          <Button disabled>Vô hiệu</Button>
          <Button
            loading={loading}
            onClick={() => {
              setLoading(true)
              window.setTimeout(() => setLoading(false), 1500)
            }}
          >
            {loading ? t('form.submitting') : 'Gửi (loading)'}
          </Button>
          <ButtonLink to="/__kit" icon="arrow-right" variant="secondary">
            Liên kết dạng nút
          </ButtonLink>
        </div>
      </Section>

      <Section title="FormField / Input / Textarea / Select">
        <div className={styles.grid}>
          <FormField label="Tên công việc" required hint="Ví dụ: Thiết kế màn hình đăng nhập">
            <Input placeholder="Nhập tên" />
          </FormField>
          <FormField label="Liên kết minh chứng" required error="Đường dẫn chưa đúng định dạng (bắt đầu bằng https://)">
            <Input defaultValue="drive" />
          </FormField>
          <FormField label="Độ khó">
            <Select defaultValue="m">
              <option value="e">Dễ</option>
              <option value="m">Vừa</option>
              <option value="h">Khó</option>
            </Select>
          </FormField>
          <FormField label="Ghi chú" hint="Không bắt buộc">
            <Textarea />
          </FormField>
        </div>
        <InlineError message="Không gửi được. Nội dung bạn nhập vẫn được giữ lại." onRetry={() => toast.success('Đã thử lại')} />
        <InlineError
          title="Đã có lỗi xảy ra"
          error={new ApiError({ status: 500, code: 'INTERNAL', message: 'x', traceId: '8f3a-demo-trace' })}
          tone="warning"
        />
      </Section>

      <Section title="TaskCard / HelpRequestCard / ContributionCard">
        <div className={styles.grid}>
          <TaskCard
            title="Thiết kế màn hình đăng nhập"
            href="/groups/demo/tasks/1"
            assigneeName="Nguyễn Văn An"
            deadline={IN_HOURS(30)}
            difficultyLabel="Vừa"
            pointsLabel={formatPointsLabel(amount(100_000), { signed: true })}
            status={{ tone: 'info', label: 'Đang thực hiện' }}
            chips={[{ tone: 'pending', label: 'Chờ TL duyệt' }]}
            actions={<Button variant="secondary">Cập nhật checkpoint</Button>}
          />
          <TaskCard
            title="Viết API thông báo"
            assigneeName="Phạm Dũng"
            deadline={HOURS_AGO(5)}
            status={{ tone: 'warning', label: 'Cần sửa/cập nhật' }}
            chips={[{ tone: 'danger', label: 'Trễ deadline' }]}
            accent="warning"
          />
          <HelpRequestCard
            title="Cần hỗ trợ phần kiểm thử"
            href="/groups/demo/help/1"
            taskTitle="Task T"
            requesterName="Lê Hoàng"
            difficultyLabel="Khó"
            effortLabel="~6 giờ"
            deadline={IN_HOURS(48)}
            pointsLabel={formatPointsLabel(amount(100_000), { signed: true })}
            offersCount={1}
            status={{ tone: 'info', label: 'Đang mở' }}
            actions={<Button>Mình có thể hỗ trợ</Button>}
          />
          <ContributionCard
            title="Hỗ trợ làm thay task T"
            href="/groups/demo/points"
            kindLabel="Hỗ trợ làm thay toàn bộ task"
            taskTitle="Task T"
            pointsLabel={formatPointsLabel({ pointUnits: 40_000, percentLabel: '+4,0000' })}
            confirmedBy="Nguyễn Văn An (TL)"
            confirmedAt={HOURS_AGO(3)}
            status={{ tone: 'success', label: 'Đã xác nhận' }}
            note="Đã nộp kết quả đúng phạm vi."
          />
        </div>
      </Section>

      <Section title="Timeline / CheckpointPrompt">
        <CheckpointPrompt description={`Giờ chốt: ${formatDateTime(IN_HOURS(7))}`} action={<Button>Cập nhật ngay</Button>} />
        <CheckpointPrompt done />
        <Card>
          <Timeline label="Lịch sử task">
            <TimelineEntry title="TL đã duyệt kết quả" tone="success" at={HOURS_AGO(2)} actorName="Nguyễn Văn An" />
            <TimelineEntry title="Kết quả đã gửi, chờ xác nhận" tone="pending" at={HOURS_AGO(20)} actorName="Trần Thị Bình">
              <a href="https://example.com">Liên kết minh chứng</a>
            </TimelineEntry>
            <TimelineEntry title="Kế hoạch đã được duyệt" tone="info" at={HOURS_AGO(72)} actorName="Nguyễn Văn An" />
            <TimelineEntry title="Task được tạo" at={HOURS_AGO(96)} />
          </Timeline>
        </Card>
      </Section>

      <Section title="NotificationItem — mọi loại thông báo (chưa đọc / đã đọc)">
        <div className={styles.list}>
          {NOTIFICATION_TYPES.map((type, i) => (
            <NotificationItem
              key={type}
              type={type}
              priority={i % 2 === 0 ? NotificationPriority.ACTION_REQUIRED : NotificationPriority.INFORMATIONAL}
              read={i % 3 === 0}
              createdAt={HOURS_AGO(i * 5 + 0.1)}
              context="Nhóm Lập trình Web · Task T"
              href="/__kit"
            />
          ))}
        </div>
      </Section>

      <Section title="EmptyState / LoadingSkeleton">
        <Card>
          <EmptyState icon="tasks" title="Chưa có công việc" description="Tạo công việc đầu tiên để cả nhóm cùng theo dõi." action={<Button icon="plus">Tạo công việc đầu tiên</Button>} />
        </Card>
        <div className={styles.grid}>
          <LoadingSkeleton variant="text" lines={3} />
          <LoadingSkeleton variant="card" />
        </div>
        <LoadingSkeleton variant="list" count={2} />
      </Section>

      <Section title="GroupSwitcher / định dạng thời gian & điểm">
        <GroupSwitcher
          groups={[
            { id: 'a', name: 'Nhóm Lập trình Web', roleLabel: 'Trưởng nhóm' },
            { id: 'b', name: 'Nhóm Khảo sát thị trường', roleLabel: 'Thành viên' },
          ]}
          currentId="a"
          hrefFor={(id) => `/groups/${id}`}
          allGroupsHref="/groups"
          createHref="/groups/new"
        />
        <div className={styles.row}>
          <span>UTC {NOW}</span>
          <TimeZoneContext.Provider value="Asia/Tokyo">
            <span>→ Tokyo: {formatDateTime(NOW, 'Asia/Tokyo')}</span>
          </TimeZoneContext.Provider>
          <span>→ Hà Nội: {formatDateTime(NOW, 'Asia/Ho_Chi_Minh')}</span>
          <span>{t('copy.pointsRecorded', { points: formatPointsLabel({ pointUnits: 40_000, percentLabel: '+4,0000' }), task: 'T' })}</span>
        </div>
      </Section>

      <Section title="Modal / BottomSheet / ConfirmDialog / Toast">
        <div className={styles.row}>
          <Button variant="secondary" onClick={() => setModal(true)}>Mở Modal</Button>
          <Button variant="secondary" onClick={() => setSheet('normal')}>Mở BottomSheet</Button>
          <Button variant="secondary" onClick={() => setSheet('full')}>BottomSheet toàn màn hình</Button>
          <Button variant="secondary" onClick={() => setConfirm(true)}>Mở ConfirmDialog</Button>
        </div>
        <div className={styles.row}>
          <Button variant="ghost" onClick={() => toast.success('Đã lưu cập nhật checkpoint')}>Toast thành công</Button>
          <Button variant="ghost" onClick={() => toast.info('Kết quả đã gửi, chờ xác nhận')}>Toast thông tin</Button>
          <Button variant="ghost" onClick={() => toast.warning('Công việc có thể cần điều chỉnh')}>Toast cảnh báo</Button>
          <Button variant="ghost" onClick={() => toast.error('Không kết nối được máy chủ', 'Lỗi')}>Toast lỗi</Button>
        </div>
      </Section>

      <Modal
        open={modal}
        onClose={() => setModal(false)}
        title="Cập nhật checkpoint"
        footer={
          <>
            <Button variant="secondary" onClick={() => setModal(false)}>Hủy</Button>
            <Button onClick={() => setModal(false)}>Gửi cập nhật</Button>
          </>
        }
      >
        <FormField label="Tình trạng ngắn gọn" required>
          <Textarea />
        </FormField>
      </Modal>
      <BottomSheet open={sheet !== 'none'} fullScreen={sheet === 'full'} onClose={() => setSheet('none')} title="Thông báo">
        <div className={styles.list}>
          {NOTIFICATION_TYPES.slice(0, 6).map((type) => (
            <NotificationItem key={type} type={type} read={false} createdAt={HOURS_AGO(1)} context="Nhóm Lập trình Web" />
          ))}
        </div>
      </BottomSheet>
      <ConfirmDialog
        open={confirm}
        tone="danger"
        title="Rời khỏi màn hình này?"
        description="Các thay đổi chưa lưu sẽ bị mất."
        confirmLabel="Rời đi"
        cancelLabel="Ở lại"
        onConfirm={() => setConfirm(false)}
        onCancel={() => setConfirm(false)}
      />
    </>
  )

  if (!withShell) return <div className={styles.bare}>{body}</div>
  return (
    <AppShell
      nav={DEMO_NAV}
      unreadCount={7}
      user={{ name: 'Nguyễn Văn An', email: 'an@example.com' }}
      onSignOut={() => toast.info('Đăng xuất (demo)')}
      settingsHref="/groups/demo/settings"
      groupSwitcher={
        <GroupSwitcher
          groups={[{ id: 'a', name: 'Nhóm Lập trình Web', roleLabel: 'Trưởng nhóm' }]}
          currentId="a"
          hrefFor={(id) => `/groups/${id}`}
          allGroupsHref="/groups"
          createHref="/groups/new"
        />
      }
    >
      {body}
    </AppShell>
  )
}
