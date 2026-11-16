package app.hanare.support;

import app.hanare.points.Refs.PageOf;
import app.hanare.security.CurrentUser;
import app.hanare.support.SupportDtos.CreateRiskReportRequest;
import app.hanare.support.SupportDtos.CreateSupportRequestRequest;
import app.hanare.support.SupportDtos.ResolveRiskRequest;
import app.hanare.support.SupportDtos.RiskReportDto;
import app.hanare.support.SupportDtos.SupportRequestDecisionRequest;
import app.hanare.support.SupportDtos.SupportRequestDto;
import app.hanare.support.SupportDtos.SupportRequestSummary;
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

/** Nhóm endpoint I — hỗ trợ riêng & risk (API_CONTRACT §I). */
@RestController
@RequestMapping("/api/v1")
public class SupportController {

	private final SupportRequestService requests;
	private final RiskReportService risks;

	public SupportController(SupportRequestService requests, RiskReportService risks) {
		this.requests = requests;
		this.risks = risks;
	}

	@PostMapping("/tasks/{taskId}/support-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public SupportRequestDto create(@PathVariable UUID taskId, CurrentUser user,
			@Valid @RequestBody CreateSupportRequestRequest body) {
		return requests.create(taskId, user, body);
	}

	@GetMapping("/groups/{groupId}/support-requests")
	public PageOf<SupportRequestSummary> list(@PathVariable UUID groupId, CurrentUser user,
			@RequestParam(required = false) String status, @RequestParam(required = false) UUID taskId,
			@RequestParam(required = false) String scope, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return requests.list(groupId, user, status, taskId, scope, page, size);
	}

	@GetMapping("/support-requests/{supportRequestId}")
	public SupportRequestDto get(@PathVariable UUID supportRequestId, CurrentUser user) {
		return requests.get(supportRequestId, user);
	}

	@PostMapping("/support-requests/{supportRequestId}/decision")
	public SupportRequestDto decide(@PathVariable UUID supportRequestId, CurrentUser user,
			@Valid @RequestBody SupportRequestDecisionRequest body) {
		return requests.decide(supportRequestId, user, body);
	}

	@GetMapping("/groups/{groupId}/risk-reports")
	public PageOf<RiskReportDto> risks(@PathVariable UUID groupId, CurrentUser user,
			@RequestParam(required = false) String status, @RequestParam(required = false) UUID taskId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return risks.list(groupId, user, status, taskId, page, size);
	}

	@PostMapping("/groups/{groupId}/risk-reports")
	@ResponseStatus(HttpStatus.CREATED)
	public RiskReportDto createRisk(@PathVariable UUID groupId, CurrentUser user,
			@Valid @RequestBody CreateRiskReportRequest body) {
		return risks.create(groupId, user, body);
	}

	@PostMapping("/risk-reports/{riskReportId}/resolve")
	public RiskReportDto resolve(@PathVariable UUID riskReportId, CurrentUser user,
			@Valid @RequestBody ResolveRiskRequest body) {
		return risks.resolve(riskReportId, user, body);
	}
}
