package app.hanare.schedule;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài {@link CycleService} (CỔNG CHUNG): chỉ đọc, bỏ qua kỳ {@code CANCELLED} khi tìm theo thời điểm/kế tiếp. */
@Service
class CycleServiceImpl implements CycleService {

	private final CycleRepository repo;
	private final Clock clock;

	CycleServiceImpl(CycleRepository repo, Clock clock) {
		this.repo = repo;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CycleRef> findById(UUID projectId, UUID cycleId) {
		return repo.find(projectId, cycleId).map(CycleRepository.CycleRow::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CycleRef> findContaining(UUID projectId, Instant at) {
		return repo.findContaining(projectId, at).map(CycleRepository.CycleRow::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CycleRef> findNext(UUID projectId, UUID cycleId) {
		return repo.find(projectId, cycleId)
				.flatMap(c -> repo.findBySequence(projectId, c.sequenceNo() + 1))
				.map(CycleRepository.CycleRow::ref);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CycleRef> findCurrent(UUID projectId) {
		return findContaining(projectId, clock.instant());
	}
}
