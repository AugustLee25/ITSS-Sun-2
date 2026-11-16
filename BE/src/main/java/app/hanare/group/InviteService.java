package app.hanare.group;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.group.GroupDtos.AcceptInviteResponse;
import app.hanare.group.GroupDtos.InviteDto;
import app.hanare.group.GroupDtos.InvitePreviewDto;
import app.hanare.schedule.ScheduleLabels;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mã/link mời: tạo, xem, thu hồi, xem trước (public) và tham gia. */
@Service
class InviteService {

	static final Duration VALIDITY = Duration.ofDays(14);
	private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	private static final int CODE_LENGTH = 8;
	private static final Pattern CODE_FORMAT = Pattern.compile("^[A-Z0-9]{6,40}$");

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final GroupReader groups;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	InviteService(JdbcClient jdbc, GroupAccess access, GroupReader groups, Clock clock) {
		this.jdbc = jdbc;
		this.access = access;
		this.groups = groups;
		this.clock = clock;
	}

	private record InviteRow(UUID id, UUID projectId, String code, UUID createdBy, String createdByName, Instant createdAt,
			Instant expiresAt, Instant revokedAt) {

		String status(Instant now) {
			if (revokedAt != null) {
				return "REVOKED";
			}
			return expiresAt != null && !expiresAt.isAfter(now) ? "EXPIRED" : "ACTIVE";
		}

		InviteDto dto(Instant now) {
			return new InviteDto(code, status(now), "/join/" + code, new UserRef(createdBy, createdByName),
					ScheduleLabels.ms(createdAt), ScheduleLabels.ms(expiresAt));
		}
	}

	private static final String SELECT = """
			SELECT i.id, i.project_id, i.code, i.created_by, u.name AS created_by_name, i.created_at, i.expires_at, i.revoked_at
			  FROM project_invitations i JOIN users u ON u.id = i.created_by
			""";

	private static InviteRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new InviteRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("code"),
				rs.getObject("created_by", UUID.class), rs.getString("created_by_name"), DbTime.instant(rs, "created_at"),
				DbTime.instant(rs, "expires_at"), DbTime.instant(rs, "revoked_at"));
	}

	private Optional<InviteRow> latest(UUID projectId) {
		return jdbc.sql(SELECT + " WHERE i.project_id = :p ORDER BY i.created_at DESC, i.id LIMIT 1")
				.param("p", projectId).query(InviteService::row).optional();
	}

	private Optional<InviteRow> byCode(String raw) {
		String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
		if (!CODE_FORMAT.matcher(code).matches()) {
			return Optional.empty();
		}
		return jdbc.sql(SELECT + " WHERE i.code = :c").param("c", code).query(InviteService::row).optional();
	}

	private static ApiException notFound() {
		return ApiException.of(HttpStatus.NOT_FOUND, "INVITE_NOT_FOUND", "Không tìm thấy lời mời. Hãy kiểm tra lại mã hoặc xin TL mã mới.");
	}

	/** Tạo mã mới và thu hồi mã còn hiệu lực trước đó. Gọi trong transaction của người gọi (đã là TL). */
	InviteDto issue(UUID projectId, UUID creatorId) {
		jdbc.sql("SELECT id FROM projects WHERE id = :p FOR UPDATE").param("p", projectId).query(UUID.class).optional();
		Instant now = clock.instant();
		jdbc.sql("UPDATE project_invitations SET revoked_at = :now WHERE project_id = :p AND revoked_at IS NULL")
				.param("now", DbTime.toDb(now)).param("p", projectId).update();
		for (int attempt = 0; attempt < 8; attempt++) {
			String code = newCode();
			Optional<UUID> id = jdbc.sql("""
					INSERT INTO project_invitations (project_id, code, created_by, created_at, expires_at)
					VALUES (:p, :c, :by, :now, :exp) ON CONFLICT (code) DO NOTHING RETURNING id
					""")
					.param("p", projectId).param("c", code).param("by", creatorId).param("now", DbTime.toDb(now))
					.param("exp", DbTime.toDb(now.plus(VALIDITY))).query(UUID.class).optional();
			if (id.isPresent()) {
				return latest(projectId).orElseThrow().dto(now);
			}
		}
		throw new IllegalStateException("Không sinh được mã mời duy nhất");
	}

	private String newCode() {
		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return sb.toString();
	}

	@Transactional
	InviteDto create(UUID groupId, CurrentUser user) {
		access.requireLeader(groupId, user);
		return issue(groupId, user.id());
	}

	@Transactional(readOnly = true)
	InviteDto get(UUID groupId, CurrentUser user) {
		access.requireLeader(groupId, user);
		InviteRow row = latest(groupId).filter(r -> r.revokedAt() == null).orElseThrow(InviteService::notFound);
		return row.dto(clock.instant());
	}

	@Transactional
	void revoke(UUID groupId, CurrentUser user) {
		access.requireLeader(groupId, user);
		int updated = jdbc.sql("UPDATE project_invitations SET revoked_at = :now WHERE project_id = :p AND revoked_at IS NULL")
				.param("now", DbTime.toDb(clock.instant())).param("p", groupId).update();
		if (updated == 0) {
			throw notFound();
		}
	}

	/** Lời mời còn hiệu lực, nếu không → 404/410. */
	private InviteRow requireActive(String code, Instant now) {
		InviteRow row = byCode(code).orElseThrow(InviteService::notFound);
		return switch (row.status(now)) {
			case "REVOKED" -> throw ApiException.of(HttpStatus.GONE, "INVITE_REVOKED",
					"Lời mời này đã bị thu hồi. Hãy xin TL gửi mã mới.");
			case "EXPIRED" -> throw ApiException.of(HttpStatus.GONE, "INVITE_EXPIRED",
					"Lời mời này đã hết hạn. Hãy xin TL gửi mã mới.");
			default -> row;
		};
	}

	@Transactional(readOnly = true)
	InvitePreviewDto preview(String code, Optional<CurrentUser> viewer) {
		Instant now = clock.instant();
		InviteRow row = requireActive(code, now);
		UserRef leader = jdbc.sql("""
				SELECT u.id, u.name FROM project_members m JOIN users u ON u.id = m.user_id
				 WHERE m.project_id = :p AND m.role = 'LEAD' AND m.left_at IS NULL
				""")
				.param("p", row.projectId()).query((rs, i) -> new UserRef(rs.getObject("id", UUID.class), rs.getString("name")))
				.single();
		var group = jdbc.sql("""
				SELECT p.name, p.course_name,
				       (SELECT count(*) FROM project_members m WHERE m.project_id = p.id AND m.left_at IS NULL) AS members
				  FROM projects p WHERE p.id = :p
				""")
				.param("p", row.projectId())
				.query((rs, i) -> new Object[] { rs.getString("name"), rs.getString("course_name"), rs.getInt("members") })
				.single();
		Boolean alreadyMember = viewer.map(u -> access.isActiveMember(row.projectId(), u.id())).orElse(null);
		return new InvitePreviewDto(row.code(), "ACTIVE", (String) group[0], (String) group[1], leader, (Integer) group[2],
				ScheduleLabels.ms(row.expiresAt()), alreadyMember, Boolean.TRUE.equals(alreadyMember) ? row.projectId() : null);
	}

	@Transactional
	AcceptInviteResponse accept(String code, CurrentUser user) {
		Instant now = clock.instant();
		InviteRow row = requireActive(code, now);
		// Một câu lệnh: thêm mới, hoặc quay lại nhóm nếu đã rời (left_at); đã đang hoạt động → không có dòng trả về.
		Optional<UUID> joined = jdbc.sql("""
				INSERT INTO project_members (project_id, user_id, role, joined_at) VALUES (:p, :u, 'MEMBER', :now)
				ON CONFLICT (project_id, user_id)
				DO UPDATE SET left_at = NULL, joined_at = :now, role = 'MEMBER' WHERE project_members.left_at IS NOT NULL
				RETURNING user_id
				""")
				.param("p", row.projectId()).param("u", user.id()).param("now", DbTime.toDb(now)).query(UUID.class).optional();
		if (joined.isEmpty()) {
			throw ApiException.of(HttpStatus.CONFLICT, "ALREADY_MEMBER", "Bạn đã là thành viên của nhóm này.");
		}
		return new AcceptInviteResponse(groups.load(row.projectId(), user.id()));
	}
}
