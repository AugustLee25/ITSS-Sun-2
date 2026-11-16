package app.hanare.schedule;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * CỔNG CHUNG (BE-A cài đặt trong module {@code schedule}; BE-B1/B2 chỉ gọi).
 * Kỳ báo cáo là {@code (period_start_at, closes_at]} — deadline đúng {@code closes_at} thuộc kỳ này (spec 0.5, C01).
 */
public interface CycleService {

	Optional<CycleRef> findById(UUID projectId, UUID cycleId);

	/** Kỳ chứa thời điểm {@code at} theo quy tắc {@code (period_start_at, closes_at]}; rỗng nếu ngoài mọi kỳ. */
	Optional<CycleRef> findContaining(UUID projectId, Instant at);

	/** Kỳ kế tiếp (sequence_no + 1) chưa bị CANCELLED. */
	Optional<CycleRef> findNext(UUID projectId, UUID cycleId);

	/** Kỳ chứa "bây giờ" (theo bean Clock). */
	Optional<CycleRef> findCurrent(UUID projectId);

	/** Ảnh chụp bất biến của một dòng {@code checkpoint_cycles}. */
	record CycleRef(
			UUID id,
			UUID projectId,
			int sequenceNo,
			UUID previousCycleId,
			Instant periodStartAt,
			Instant opensAt,
			Instant reminderAt,
			Instant closesAt,
			String status,
			Instant closedAt,
			Instant finalizedAt) {
	}
}
