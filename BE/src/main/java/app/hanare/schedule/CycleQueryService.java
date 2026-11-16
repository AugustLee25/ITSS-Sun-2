package app.hanare.schedule;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import app.hanare.group.PageParams;
import app.hanare.group.PageResponse;
import app.hanare.schedule.CycleRepository.CycleRow;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDetailDto;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDto;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import app.hanare.security.GroupMembership;
import app.hanare.task.CycleLifecycle;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Danh sách / chi tiết kỳ checkpoint (đọc). */
@Service
class CycleQueryService {

	private static final Set<String> STATUSES = Set.of("SCHEDULED", "OPEN", "CLOSED", "FINALIZED", "CANCELLED");

	private final CycleRepository repo;
	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final Clock clock;
	private final ObjectProvider<CycleLifecycle> lifecycle;

	CycleQueryService(CycleRepository repo, JdbcClient jdbc, GroupAccess access, Clock clock,
			ObjectProvider<CycleLifecycle> lifecycle) {
		this.lifecycle = lifecycle;
		this.repo = repo;
		this.jdbc = jdbc;
		this.access = access;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	PageResponse<CheckpointCycleDto> list(UUID groupId, CurrentUser user, String status, String sort, Integer page,
			Integer size) {
		access.requireMember(groupId, user);
		if (status != null && !STATUSES.contains(status)) {
			throw ApiException.validation("status", "Trạng thái kỳ không hợp lệ.");
		}
		if (sort != null && !sort.equals("asc") && !sort.equals("desc")) {
			throw ApiException.validation("sort", "Sắp xếp phải là asc hoặc desc.");
		}
		PageParams params = PageParams.of(page, size);
		Instant now = clock.instant();
		List<CheckpointCycleDto> items = repo
				.list(groupId, status, !"asc".equals(sort), params.offset(), params.size()).stream()
				.map(c -> CycleViews.toDto(c, now)).toList();
		return PageResponse.of(items, params, repo.count(groupId, status));
	}

	@Transactional(readOnly = true)
	CheckpointCycleDetailDto current(UUID groupId, CurrentUser user) {
		GroupMembership m = access.requireMember(groupId, user);
		CycleRow cycle = repo.findContaining(groupId, clock.instant())
				.orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Nhóm chưa có kỳ checkpoint nào đang diễn ra."));
		return detail(cycle, m);
	}

	@Transactional(readOnly = true)
	CheckpointCycleDetailDto get(UUID groupId, UUID cycleId, CurrentUser user) {
		GroupMembership m = access.requireMember(groupId, user);
		CycleRow cycle = repo.find(groupId, cycleId)
				.orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ checkpoint."));
		return detail(cycle, m);
	}

	/**
	 * Số thành viên đã gửi báo cáo tính từ {@code checkpoint_reports} (chỉ đọc). {@code leadSummary} chỉ TL thấy, do {@link CycleLifecycle#leadSummary}
	 * (module task) dựng.
	 */
	private CheckpointCycleDetailDto detail(CycleRow cycle, GroupMembership viewer) {
		Instant now = clock.instant();
		UUID next = repo.findNextOf(cycle.projectId(), cycle.id()).map(CycleRow::id).orElse(null);
		int active = jdbc.sql("SELECT count(*) FROM project_members WHERE project_id = :p AND left_at IS NULL")
				.param("p", cycle.projectId()).query(Integer.class).single();
		int reported = jdbc.sql("""
				SELECT count(DISTINCT r.member_id) FROM checkpoint_reports r
				  JOIN project_members m ON m.project_id = :p AND m.user_id = r.member_id AND m.left_at IS NULL
				 WHERE r.cycle_id = :c AND r.status = 'SUBMITTED'
				""")
				.param("p", cycle.projectId()).param("c", cycle.id()).query(Integer.class).single();
		Object summary = viewer.isLeader() ? lifecycle.getObject().leadSummary(cycle.projectId(), cycle.id(), false) : null;
		return CycleViews.toDetail(cycle, now, next, active, reported, summary);
	}
}
