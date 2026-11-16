package app.hanare.help;

import app.hanare.help.HelpDtos.AcceptOfferRequest;
import app.hanare.help.HelpDtos.AcceptOfferResponse;
import app.hanare.help.HelpDtos.CancelHelpRequestRequest;
import app.hanare.help.HelpDtos.CreateHelpRequestRequest;
import app.hanare.help.HelpDtos.CreateOfferRequest;
import app.hanare.help.HelpDtos.HelpRequestCard;
import app.hanare.help.HelpDtos.HelpRequestDetail;
import app.hanare.help.HelpDtos.ReconfirmOfferRequest;
import app.hanare.help.HelpDtos.ReportUnableRequest;
import app.hanare.help.HelpDtos.ResolveDiscussionRequest;
import app.hanare.help.HelpDtos.SubmitSupportResultRequest;
import app.hanare.help.HelpDtos.SubmitSupportResultResponse;
import app.hanare.help.HelpDtos.SupportOfferDto;
import app.hanare.help.HelpDtos.SupportWorkDto;
import app.hanare.help.HelpDtos.UpdateHelpRequestRequest;
import app.hanare.points.Refs.PageOf;
import app.hanare.security.CurrentUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nhóm endpoint J — hỗ trợ công khai, tự nguyện đề nghị, bàn giao (API_CONTRACT §J). */
@RestController
@RequestMapping("/api/v1")
public class HelpController {

	private final HelpRequestService requests;
	private final OfferService offers;
	private final SupportWorkService works;

	public HelpController(HelpRequestService requests, OfferService offers, SupportWorkService works) {
		this.requests = requests;
		this.offers = offers;
		this.works = works;
	}

	@GetMapping("/groups/{groupId}/help-requests")
	public PageOf<HelpRequestCard> list(@PathVariable UUID groupId, CurrentUser user,
			@RequestParam(required = false) String tab, @RequestParam(required = false) String difficulty,
			@RequestParam(required = false) Instant deadlineFrom, @RequestParam(required = false) Instant deadlineTo,
			@RequestParam(required = false) Long minPointUnits, @RequestParam(required = false) Long maxPointUnits,
			@RequestParam(required = false) Integer maxEffortMinutes, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return requests.list(groupId, user, tab, difficulty, deadlineFrom, deadlineTo, minPointUnits, maxPointUnits,
				maxEffortMinutes, page, size);
	}

	@PostMapping("/groups/{groupId}/help-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public HelpRequestDetail create(@PathVariable UUID groupId, CurrentUser user,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody CreateHelpRequestRequest body) {
		return requests.create(groupId, user, key, body);
	}

	@GetMapping("/help-requests/{helpRequestId}")
	public HelpRequestDetail get(@PathVariable UUID helpRequestId, CurrentUser user) {
		return requests.get(helpRequestId, user);
	}

	@PatchMapping("/help-requests/{helpRequestId}")
	public HelpRequestDetail update(@PathVariable UUID helpRequestId, CurrentUser user,
			@Valid @RequestBody UpdateHelpRequestRequest body) {
		return requests.update(helpRequestId, user, body);
	}

	@PostMapping("/help-requests/{helpRequestId}/cancel")
	public HelpRequestDetail cancel(@PathVariable UUID helpRequestId, CurrentUser user,
			@Valid @RequestBody CancelHelpRequestRequest body) {
		return requests.cancel(helpRequestId, user, body);
	}

	@PostMapping("/help-requests/{helpRequestId}/offers")
	@ResponseStatus(HttpStatus.CREATED)
	public SupportOfferDto createOffer(@PathVariable UUID helpRequestId, CurrentUser user,
			@Valid @RequestBody CreateOfferRequest body) {
		return offers.create(helpRequestId, user, body);
	}

	@PostMapping("/support-offers/{offerId}/withdraw")
	public SupportOfferDto withdraw(@PathVariable UUID offerId, CurrentUser user) {
		return offers.withdraw(offerId, user);
	}

	@PostMapping("/support-offers/{offerId}/reconfirm")
	public SupportOfferDto reconfirm(@PathVariable UUID offerId, CurrentUser user,
			@Valid @RequestBody ReconfirmOfferRequest body) {
		return offers.reconfirm(offerId, user, body);
	}

	@PostMapping("/help-requests/{helpRequestId}/accept-offer")
	public AcceptOfferResponse accept(@PathVariable UUID helpRequestId, CurrentUser user,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody AcceptOfferRequest body) {
		return offers.accept(helpRequestId, user, key, body);
	}

	@GetMapping("/support-works/{workId}")
	public SupportWorkDto work(@PathVariable UUID workId, CurrentUser user) {
		return works.get(workId, user);
	}

	@PostMapping("/support-works/{workId}/submissions")
	@ResponseStatus(HttpStatus.CREATED)
	public SubmitSupportResultResponse submit(@PathVariable UUID workId, CurrentUser user,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody SubmitSupportResultRequest body) {
		return works.submit(workId, user, key, body);
	}

	@PostMapping("/support-works/{workId}/unable")
	public SupportWorkDto unable(@PathVariable UUID workId, CurrentUser user, @Valid @RequestBody ReportUnableRequest body) {
		return works.unable(workId, user, body);
	}

	@PostMapping("/support-works/{workId}/resolve-discussion")
	public SupportWorkDto resolve(@PathVariable UUID workId, CurrentUser user,
			@RequestHeader(value = "Idempotency-Key", required = false) String key,
			@Valid @RequestBody ResolveDiscussionRequest body) {
		return works.resolve(workId, user, key, body);
	}
}
