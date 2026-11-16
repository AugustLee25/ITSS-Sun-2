package app.hanare.schedule;

/**
 * CỔNG CHUNG: job của {@code schedule} (BE-A) gọi MỌI bean {@code CutoffHook} khi một kỳ tới {@code closes_at},
 * trong một transaction, sau khi đã chuyển kỳ sang {@code CLOSED}. BE-B1 (module {@code task}) cài đặt:
 * chụp snapshot task/bản nộp tại giờ chốt ({@code checkpoint_task_snapshots}), dựng {@code LeadCycleSummary} và
 * publish đúng MỘT {@code CHECKPOINT_REVIEW_REQUIRED} cho TL (dedup {@code checkpoint:{cycleId}:review-required}).
 *
 * <p>Phải idempotent: job chạy lại/đồng thời cho cùng kỳ không được tạo bản ghi thứ hai.
 */
public interface CutoffHook {

	void onCycleClosed(CycleService.CycleRef cycle);
}
