package app.hanare.dashboard;

import app.hanare.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mục D của API_CONTRACT: dashboard S03 + chi tiết một thành viên (2 endpoint). */
@RestController
@RequestMapping("/api/v1/groups/{groupId}/dashboard")
public class DashboardController {

	private final DashboardService service;

	public DashboardController(DashboardService service) {
		this.service = service;
	}

	@GetMapping
	Map<String, Object> dashboard(@PathVariable UUID groupId, CurrentUser user, @RequestParam(required = false) UUID cycleId) {
		return service.dashboard(groupId, cycleId, user);
	}

	@GetMapping("/members/{userId}")
	Map<String, Object> member(@PathVariable UUID groupId, @PathVariable UUID userId, CurrentUser user,
			@RequestParam(required = false) UUID cycleId) {
		return service.memberDetail(groupId, userId, cycleId, user);
	}
}
