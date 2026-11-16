package app.hanare.schedule;

import app.hanare.group.ItemsResponse;
import app.hanare.group.PageResponse;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDetailDto;
import app.hanare.schedule.ScheduleDtos.CheckpointCycleDto;
import app.hanare.schedule.ScheduleDtos.CheckpointScheduleDto;
import app.hanare.schedule.ScheduleDtos.SaveScheduleRequest;
import app.hanare.schedule.ScheduleDtos.SaveScheduleResponse;
import app.hanare.schedule.ScheduleDtos.SchedulePreviewRequest;
import app.hanare.schedule.ScheduleDtos.SchedulePreviewResponse;
import app.hanare.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Mục C của hợp đồng (trừ {@code …/lead-summary}, thuộc module task). */
@RestController
@RequestMapping("/api/v1")
class ScheduleController {

	private final ScheduleService schedules;
	private final CycleQueryService cycles;

	ScheduleController(ScheduleService schedules, CycleQueryService cycles) {
		this.schedules = schedules;
		this.cycles = cycles;
	}

	@PostMapping("/checkpoint-schedules/preview")
	SchedulePreviewResponse preview(@Valid @RequestBody SchedulePreviewRequest request, CurrentUser user) {
		return schedules.preview(user, request);
	}

	@GetMapping("/groups/{groupId}/checkpoint-schedules")
	ItemsResponse<CheckpointScheduleDto> listSchedules(@PathVariable UUID groupId, CurrentUser user) {
		return schedules.list(groupId, user);
	}

	@PostMapping("/groups/{groupId}/checkpoint-schedules")
	@ResponseStatus(HttpStatus.CREATED)
	SaveScheduleResponse saveSchedule(@PathVariable UUID groupId, @Valid @RequestBody SaveScheduleRequest request,
			CurrentUser user) {
		return schedules.save(groupId, user, request);
	}

	@GetMapping("/groups/{groupId}/checkpoint-cycles")
	PageResponse<CheckpointCycleDto> listCycles(@PathVariable UUID groupId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, CurrentUser user) {
		return cycles.list(groupId, user, status, sort, page, size);
	}

	@GetMapping("/groups/{groupId}/checkpoint-cycles/current")
	CheckpointCycleDetailDto currentCycle(@PathVariable UUID groupId, CurrentUser user) {
		return cycles.current(groupId, user);
	}

	@GetMapping("/groups/{groupId}/checkpoint-cycles/{cycleId}")
	CheckpointCycleDetailDto cycle(@PathVariable UUID groupId, @PathVariable UUID cycleId, CurrentUser user) {
		return cycles.get(groupId, cycleId, user);
	}
}
