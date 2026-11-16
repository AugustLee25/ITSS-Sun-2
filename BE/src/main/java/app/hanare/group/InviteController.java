package app.hanare.group;

import app.hanare.group.GroupDtos.AcceptInviteResponse;
import app.hanare.group.GroupDtos.InvitePreviewDto;
import app.hanare.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lời mời theo mã: xem trước (PUBLIC, có token thì thêm alreadyMember/groupId) và tham gia (AUTH). */
@RestController
@RequestMapping("/api/v1/invites")
class InviteController {

	private final InviteService invites;

	InviteController(InviteService invites) {
		this.invites = invites;
	}

	@GetMapping("/{code}")
	InvitePreviewDto preview(@PathVariable String code) {
		return invites.preview(code, CurrentUser.find());
	}

	@PostMapping("/{code}/accept")
	AcceptInviteResponse accept(@PathVariable String code, CurrentUser user) {
		return invites.accept(code, user);
	}
}
