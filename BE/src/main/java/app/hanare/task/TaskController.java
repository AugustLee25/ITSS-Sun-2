package app.hanare.task;

import app.hanare.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Mục F của API_CONTRACT: task, kế hoạch, revision, tiến độ (12 endpoint). */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

	private final TaskService service;

	public TaskController(TaskService service) {
		this.service = service;
	}

	@GetMapping("/groups/{groupId}/tasks")
	Map<String, Object> list(@PathVariable UUID groupId, CurrentUser user, @RequestParam Map<String, String> q) {
		return service.list(groupId, user, q);
	}

	@PostMapping("/groups/{groupId}/tasks")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> create(@PathVariable UUID groupId, CurrentUser user, @RequestBody TaskService.CreateTaskRequest body) {
		return service.create(groupId, user, body);
	}

	@GetMapping("/tasks/{taskId}")
	Map<String, Object> get(@PathVariable UUID taskId, CurrentUser user) {
		return service.get(taskId, user);
	}

	@GetMapping("/tasks/{taskId}/history")
	Map<String, Object> history(@PathVariable UUID taskId, CurrentUser user, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return service.history(taskId, user, page, size);
	}

	@PatchMapping("/plans/{planId}")
	Map<String, Object> update(@PathVariable UUID planId, CurrentUser user, @RequestBody TaskService.UpdatePlanRequest body) {
		return service.updatePlan(planId, user, body);
	}

	@PostMapping("/plans/{planId}/submit")
	Map<String, Object> submit(@PathVariable UUID planId, CurrentUser user, @RequestBody TaskService.VersionedRequest body) {
		return service.submitPlan(planId, user, body);
	}

	@PostMapping("/plans/{planId}/withdraw")
	Map<String, Object> withdraw(@PathVariable UUID planId, CurrentUser user, @RequestBody TaskService.VersionedRequest body) {
		return service.withdrawPlan(planId, user, body);
	}

	@PostMapping("/plans/{planId}/review")
	Map<String, Object> review(@PathVariable UUID planId, CurrentUser user, @RequestBody TaskService.ReviewPlanRequest body) {
		return service.reviewPlan(planId, user, body);
	}

	@PostMapping("/tasks/{taskId}/plan-revisions")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> propose(@PathVariable UUID taskId, CurrentUser user, @RequestBody TaskService.ProposeChangeRequest body) {
		return service.proposeChange(taskId, user, body);
	}

	@PostMapping("/tasks/{taskId}/start")
	Map<String, Object> start(@PathVariable UUID taskId, CurrentUser user, @RequestBody TaskService.VersionedRequest body) {
		return service.start(taskId, user, body);
	}

	@PostMapping("/tasks/{taskId}/progress")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> progress(@PathVariable UUID taskId, CurrentUser user, @RequestBody TaskService.ProgressUpdateRequest body) {
		return service.progress(taskId, user, body);
	}

	@PutMapping("/tasks/{taskId}/independent-reviewer")
	Map<String, Object> independentReviewer(@PathVariable UUID taskId, CurrentUser user,
			@RequestBody TaskService.SetIndependentReviewerRequest body) {
		return service.setIndependentReviewer(taskId, user, body);
	}
}
