package app.hanare.security;

import java.util.UUID;

/** Tư cách thành viên đang hoạt động (left_at IS NULL) của một người trong một nhóm. */
public record GroupMembership(UUID projectId, UUID userId, GroupRole role) {

	public boolean isLeader() {
		return role == GroupRole.LEAD;
	}
}
