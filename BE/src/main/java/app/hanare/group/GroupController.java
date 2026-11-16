package app.hanare.group;

import app.hanare.group.GroupDtos.CreateGroupRequest;
import app.hanare.group.GroupDtos.CreateGroupResponse;
import app.hanare.group.GroupDtos.GroupCardDto;
import app.hanare.group.GroupDtos.GroupDto;
import app.hanare.group.GroupDtos.InviteDto;
import app.hanare.group.GroupDtos.MemberDto;
import app.hanare.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Mục B của hợp đồng: nhóm, thành viên, mã mời của nhóm. */
@RestController
@RequestMapping("/api/v1/groups")
class GroupController {

	private final GroupService groups;
	private final InviteService invites;

	GroupController(GroupService groups, InviteService invites) {
		this.groups = groups;
		this.invites = invites;
	}

	@GetMapping
	ItemsResponse<GroupCardDto> list(CurrentUser user) {
		return groups.list(user);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CreateGroupResponse create(@Valid @RequestBody CreateGroupRequest request, CurrentUser user) {
		return groups.create(user, request);
	}

	@GetMapping("/{groupId}")
	GroupDto get(@PathVariable UUID groupId, CurrentUser user) {
		return groups.get(groupId, user);
	}

	/** Body đọc dạng JSON thô để phân biệt "không gửi courseName" với "courseName = null (xóa)". */
	@PatchMapping("/{groupId}")
	GroupDto update(@PathVariable UUID groupId, @RequestBody JsonNode body, CurrentUser user) {
		return groups.update(groupId, user, body);
	}

	@GetMapping("/{groupId}/members")
	ItemsResponse<MemberDto> members(@PathVariable UUID groupId, CurrentUser user) {
		return groups.members(groupId, user);
	}

	@GetMapping("/{groupId}/invite")
	InviteDto invite(@PathVariable UUID groupId, CurrentUser user) {
		return invites.get(groupId, user);
	}

	@PostMapping("/{groupId}/invite")
	@ResponseStatus(HttpStatus.CREATED)
	InviteDto createInvite(@PathVariable UUID groupId, CurrentUser user) {
		return invites.create(groupId, user);
	}

	@DeleteMapping("/{groupId}/invite")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revokeInvite(@PathVariable UUID groupId, CurrentUser user) {
		invites.revoke(groupId, user);
	}
}
