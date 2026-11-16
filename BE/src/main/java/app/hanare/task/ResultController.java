package app.hanare.task;

import app.hanare.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Mục G của API_CONTRACT: nộp minh chứng, duyệt kết quả, chuyển kỳ, hàng đợi duyệt (6 endpoint). */
@RestController
@RequestMapping("/api/v1")
public class ResultController {

	private final ResultService service;

	public ResultController(ResultService service) {
		this.service = service;
	}

	@PostMapping("/task-rounds/{roundId}/submissions")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> submit(@PathVariable UUID roundId, CurrentUser user, @RequestBody ResultService.SubmitResultRequest body) {
		return service.submit(roundId, user, body);
	}

	@GetMapping("/task-rounds/{roundId}/submissions")
	Map<String, Object> submissions(@PathVariable UUID roundId, CurrentUser user) {
		return service.submissions(roundId, user);
	}

	@GetMapping("/task-submissions/{submissionId}/review-preview")
	Map<String, Object> preview(@PathVariable UUID submissionId, CurrentUser user) {
		return service.reviewPreview(submissionId, user);
	}

	@PostMapping("/task-submissions/{submissionId}/review")
	Map<String, Object> review(@PathVariable UUID submissionId, CurrentUser user, @RequestBody ResultService.ReviewResultRequest body) {
		return service.review(submissionId, user, body);
	}

	@PostMapping("/task-rounds/{roundId}/carryover")
	ResponseEntity<Map<String, Object>> carryover(@PathVariable UUID roundId, CurrentUser user,
			@RequestBody ResultService.CarryoverRequest body) {
		ResultService.ResponseEntityLike r = service.carryover(roundId, user, body);
		return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.body());
	}

	@GetMapping("/groups/{groupId}/checkpoint-cycles/{cycleId}/review-queue")
	Map<String, Object> queue(@PathVariable UUID groupId, @PathVariable UUID cycleId, CurrentUser user) {
		return service.reviewQueue(groupId, cycleId, user);
	}
}
