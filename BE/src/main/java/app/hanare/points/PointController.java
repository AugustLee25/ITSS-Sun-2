package app.hanare.points;

import app.hanare.points.PointDtos.ActivatePointPolicyRequest;
import app.hanare.points.PointDtos.ActivatePointPolicyResponse;
import app.hanare.points.PointDtos.GroupPointsSummary;
import app.hanare.points.PointDtos.LedgerEntryDetail;
import app.hanare.points.PointDtos.MyPointsResponse;
import app.hanare.points.PointDtos.PointHistoryItem;
import app.hanare.points.PointDtos.PointPolicyPreviewRequest;
import app.hanare.points.PointDtos.PointPolicyPreviewResponse;
import app.hanare.points.PointDtos.PointPolicyResponse;
import app.hanare.points.PointDtos.PointTaskRow;
import app.hanare.points.PointDtos.ReverseSettlementRequest;
import app.hanare.points.PointDtos.ReverseSettlementResponse;
import app.hanare.points.PointDtos.SimulatePointsRequest;
import app.hanare.points.PointDtos.SimulatePointsResponse;
import app.hanare.points.PointDtos.SimulationCandidatesResponse;
import app.hanare.points.Refs.PageOf;
import app.hanare.security.CurrentUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nhóm endpoint K — điểm đóng góp (API_CONTRACT §K). */
@RestController
@RequestMapping("/api/v1")
public class PointController {

	private final PointPolicyService policies;
	private final PointQueryService queries;
	private final PointLedgerService ledger;

	public PointController(PointPolicyService policies, PointQueryService queries, PointLedgerService ledger) {
		this.policies = policies;
		this.queries = queries;
		this.ledger = ledger;
	}

	@GetMapping("/groups/{groupId}/point-policy")
	public PointPolicyResponse policy(@PathVariable UUID groupId, CurrentUser user) {
		return policies.get(groupId, user);
	}

	@PostMapping("/groups/{groupId}/point-policy/preview")
	public PointPolicyPreviewResponse preview(@PathVariable UUID groupId, CurrentUser user,
			@RequestBody(required = false) PointPolicyPreviewRequest body) {
		return policies.preview(groupId, user, body);
	}

	@PostMapping("/groups/{groupId}/point-policy/activate")
	@ResponseStatus(HttpStatus.CREATED)
	public ActivatePointPolicyResponse activate(@PathVariable UUID groupId, CurrentUser user,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestBody ActivatePointPolicyRequest body) {
		return policies.activate(groupId, user, key, body);
	}

	@GetMapping("/groups/{groupId}/points/me")
	public MyPointsResponse me(@PathVariable UUID groupId, CurrentUser user) {
		return queries.me(groupId, user);
	}

	@GetMapping("/groups/{groupId}/points/me/tasks")
	public PageOf<PointTaskRow> myTasks(@PathVariable UUID groupId, CurrentUser user,
			@RequestParam(required = false) String status, @RequestParam(required = false) String source,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return queries.myTasks(groupId, user, status, source, page, size);
	}

	@GetMapping("/groups/{groupId}/points/me/ledger")
	public PageOf<PointHistoryItem> myLedger(@PathVariable UUID groupId, CurrentUser user,
			@RequestParam(required = false) String group, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return queries.myLedger(groupId, user, group, page, size);
	}

	@GetMapping("/groups/{groupId}/points/ledger/{entryId}")
	public LedgerEntryDetail ledgerEntry(@PathVariable UUID groupId, @PathVariable UUID entryId, CurrentUser user) {
		return queries.ledgerDetail(groupId, entryId, user);
	}

	@GetMapping("/groups/{groupId}/points/me/simulation-candidates")
	public SimulationCandidatesResponse candidates(@PathVariable UUID groupId, CurrentUser user) {
		return queries.simulationCandidates(groupId, user);
	}

	@PostMapping("/groups/{groupId}/points/me/simulate")
	public SimulatePointsResponse simulate(@PathVariable UUID groupId, CurrentUser user,
			@RequestBody(required = false) SimulatePointsRequest body) {
		return queries.simulate(groupId, user, body);
	}

	@GetMapping("/groups/{groupId}/points/summary")
	public GroupPointsSummary summary(@PathVariable UUID groupId, CurrentUser user) {
		return queries.summary(groupId, user);
	}

	@PostMapping("/point-settlements/{settlementId}/reverse")
	public ReverseSettlementResponse reverse(@PathVariable UUID settlementId, CurrentUser user,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@RequestBody ReverseSettlementRequest body) {
		return ledger.reverse(settlementId, user, key, body);
	}
}
