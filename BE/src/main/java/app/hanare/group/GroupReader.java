package app.hanare.group;

import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.group.GroupDtos.GroupDto;
import app.hanare.schedule.ScheduleLabels;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Dựng {@link GroupDto} (dùng chung cho tạo/xem/sửa nhóm và tham gia bằng mã mời). */
@Component
class GroupReader {

	private final JdbcClient jdbc;

	GroupReader(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Dựng {@link GroupDto} cho người xem {@code viewerId} (người gọi đã được kiểm tra là thành viên). */
	GroupDto load(UUID groupId, UUID viewerId) {
		return jdbc.sql("""
				SELECT p.id, p.name, p.course_name, p.start_at, p.deadline, p.timezone, p.created_at, p.version,
				       (SELECT count(*) FROM project_members x WHERE x.project_id = p.id AND x.left_at IS NULL) AS member_count,
				       l.user_id AS leader_id, lu.name AS leader_name,
				       (SELECT v.role FROM project_members v WHERE v.project_id = p.id AND v.user_id = :viewer AND v.left_at IS NULL) AS my_role
				  FROM projects p
				  JOIN project_members l ON l.project_id = p.id AND l.role = 'LEAD' AND l.left_at IS NULL
				  JOIN users lu ON lu.id = l.user_id
				 WHERE p.id = :id
				""")
				.param("id", groupId).param("viewer", viewerId)
				.query((rs, i) -> {
					ZoneId zone = ZoneId.of(rs.getString("timezone"));
					return new GroupDto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("course_name"),
							DbTime.instant(rs, "start_at").atZone(zone).toLocalDate(),
							DbTime.instant(rs, "deadline").atZone(zone).toLocalDate(), zone.getId(), rs.getInt("member_count"),
							new UserRef(rs.getObject("leader_id", UUID.class), rs.getString("leader_name")),
							rs.getString("my_role"), ScheduleLabels.ms(DbTime.instant(rs, "created_at")), rs.getLong("version"));
				})
				.optional().orElseThrow(() -> ApiException.of(ErrorCode.NOT_A_GROUP_MEMBER, null));
	}
}
