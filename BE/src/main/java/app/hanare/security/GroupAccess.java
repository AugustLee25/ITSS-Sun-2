package app.hanare.security;

import app.hanare.common.ApiException;
import app.hanare.common.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra quyền theo nhóm trên server (BR12).
 * <ul>
 * <li>Không phải thành viên đang hoạt động -> 404 NOT_A_GROUP_MEMBER (không lộ nhóm có tồn tại hay không).</li>
 * <li>Là thành viên nhưng sai vai trò -> 403 LEAD_ONLY.</li>
 * </ul>
 * Mọi endpoint có {@code {projectId}} phải gọi {@code requireMember}/{@code requireLeader} trước khi đọc/ghi.
 */
@Component
public class GroupAccess {

	private final JdbcClient jdbc;

	public GroupAccess(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Thành viên đang hoạt động (TL hoặc Member), nếu không -> 404 NOT_A_GROUP_MEMBER. */
	public GroupMembership requireMember(UUID projectId, CurrentUser user) {
		return requireMember(projectId, user.id());
	}

	public GroupMembership requireMember(UUID projectId, UUID userId) {
		return findActive(projectId, userId).orElseThrow(() -> ApiException.of(ErrorCode.NOT_A_GROUP_MEMBER, null));
	}

	/** Phải là TL đang hoạt động: ngoài nhóm -> 404 NOT_A_GROUP_MEMBER, là Member -> 403 LEAD_ONLY. */
	public GroupMembership requireLeader(UUID projectId, CurrentUser user) {
		return requireLeader(projectId, user.id());
	}

	public GroupMembership requireLeader(UUID projectId, UUID userId) {
		GroupMembership m = requireMember(projectId, userId);
		if (!m.isLeader()) {
			throw ApiException.of(ErrorCode.LEAD_ONLY, null);
		}
		return m;
	}

	public Optional<GroupMembership> findActive(UUID projectId, UUID userId) {
		return jdbc.sql("""
				SELECT project_id, user_id, role FROM project_members
				 WHERE project_id = :projectId AND user_id = :userId AND left_at IS NULL
				""")
				.param("projectId", projectId).param("userId", userId)
				.query((rs, i) -> new GroupMembership(rs.getObject("project_id", UUID.class),
						rs.getObject("user_id", UUID.class), GroupRole.valueOf(rs.getString("role"))))
				.optional();
	}

	public boolean isActiveMember(UUID projectId, UUID userId) {
		return findActive(projectId, userId).isPresent();
	}

	/** TL đang hoạt động của nhóm (mỗi nhóm tối đa một). */
	public Optional<UUID> activeLeaderId(UUID projectId) {
		return jdbc.sql("""
				SELECT user_id FROM project_members
				 WHERE project_id = :projectId AND role = 'LEAD' AND left_at IS NULL
				""")
				.param("projectId", projectId).query(UUID.class).optional();
	}

	/** Mọi thành viên đang hoạt động (gồm TL), dùng để fan-out thông báo. */
	public List<UUID> activeMemberIds(UUID projectId) {
		return jdbc.sql("""
				SELECT user_id FROM project_members
				 WHERE project_id = :projectId AND left_at IS NULL ORDER BY joined_at, user_id
				""")
				.param("projectId", projectId).query(UUID.class).list();
	}
}
