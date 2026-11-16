package app.hanare.task;

import app.hanare.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mục H (báo cáo checkpoint của tôi, 3 endpoint) + {@code GET …/lead-summary} (mục C, do BE-B1 sở hữu). */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/checkpoint-cycles/{cycleId}")
public class ReportController {

	private final ReportService reports;
	private final CycleLifecycle lifecycle;
	private final app.hanare.security.GroupAccess access;

	public ReportController(ReportService reports, CycleLifecycle lifecycle, app.hanare.security.GroupAccess access) {
		this.reports = reports;
		this.lifecycle = lifecycle;
		this.access = access;
	}

	@GetMapping("/my-report")
	Map<String, Object> get(@PathVariable UUID groupId, @PathVariable UUID cycleId, CurrentUser user) {
		return reports.view(groupId, cycleId, user);
	}

	@PutMapping("/my-report")
	Map<String, Object> save(@PathVariable UUID groupId, @PathVariable UUID cycleId, CurrentUser user,
			@RequestBody ReportService.SaveReportRequest body) {
		return reports.save(groupId, cycleId, user, body);
	}

	@PostMapping("/my-report/submit")
	Map<String, Object> submit(@PathVariable UUID groupId, @PathVariable UUID cycleId, CurrentUser user,
			@RequestBody ReportService.SubmitReportRequest body) {
		return reports.submit(groupId, cycleId, user, body);
	}

	@GetMapping("/lead-summary")
	Map<String, Object> leadSummary(@PathVariable UUID groupId, @PathVariable UUID cycleId, CurrentUser user,
			@RequestParam(defaultValue = "CURRENT") String view) {
		access.requireLeader(groupId, user);
		if (!"CURRENT".equals(view) && !"AT_CUTOFF".equals(view)) {
			throw app.hanare.common.ApiException.validation("view", "Giá trị phải là CURRENT hoặc AT_CUTOFF");
		}
		return lifecycle.leadSummary(groupId, cycleId, "AT_CUTOFF".equals(view));
	}
}
