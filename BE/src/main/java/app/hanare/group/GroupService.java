package app.hanare.group;

import app.hanare.common.ApiError;
import app.hanare.common.ApiException;
import app.hanare.common.DbTime;
import app.hanare.common.ErrorCode;
import app.hanare.group.GroupDtos.CreateGroupRequest;
import app.hanare.group.GroupDtos.CreateGroupResponse;
import app.hanare.group.GroupDtos.GroupCardAttention;
import app.hanare.group.GroupDtos.GroupCardDto;
import app.hanare.group.GroupDtos.GroupDto;
import app.hanare.group.GroupDtos.MemberDto;
import app.hanare.notification.NotificationType;
import app.hanare.schedule.ScheduleLabels;
import app.hanare.schedule.ScheduleService;
import app.hanare.schedule.ScheduleService.InitialSchedule;
import app.hanare.schedule.ScheduleSpec;
import app.hanare.security.CurrentUser;
import app.hanare.security.GroupAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Nhóm (project): tạo (wizard S02), danh sách của tôi (S01), chi tiết/sửa, thành viên. */
@Service
class GroupService {

	private static final int MAX_YEAR = 2200;

	private final JdbcClient jdbc;
	private final GroupAccess access;
	private final ScheduleService schedules;
	private final InviteService invites;
	private final Clock clock;

	private final GroupReader reader;

	GroupService(JdbcClient jdbc, GroupAccess access, ScheduleService schedules, InviteService invites, Clock clock,
			GroupReader reader) {
		this.jdbc = jdbc;
		this.access = access;
		this.schedules = schedules;
		this.invites = invites;
		this.clock = clock;
		this.reader = reader;
	}

	// ------------------------------------------------------------------ đọc một nhóm
	@Transactional(readOnly = true)
	GroupDto get(UUID groupId, CurrentUser user) {
		access.requireMember(groupId, user);
		return load(groupId, user.id());
	}

	private GroupDto load(UUID groupId, UUID viewerId) {
		return reader.load(groupId, viewerId);
	}

	// ------------------------------------------------------------------ danh sách nhóm của tôi (S01)
	@Transactional(readOnly = true)
	ItemsResponse<GroupCardDto> list(CurrentUser user) {
		Instant now = clock.instant();
		List<String> actionTypes = actionTypesExceptReminder();
		List<GroupCardDto> items = jdbc.sql("""
				SELECT p.id, p.name, p.course_name, p.deadline, p.timezone, m.role,
				       (SELECT count(*) FROM project_members x WHERE x.project_id = p.id AND x.left_at IS NULL) AS member_count,
				       (SELECT count(*) FROM notifications n
				         WHERE n.project_id = p.id AND n.recipient_id = :me AND n.read_at IS NULL) AS unread,
				       (SELECT count(*) FROM notifications n JOIN notification_events e ON e.id = n.event_id
				         WHERE n.project_id = p.id AND n.recipient_id = :me AND n.read_at IS NULL
				           AND e.event_type IN (:actionTypes)) AS other_actions,
				       EXISTS (SELECT 1 FROM checkpoint_cycles c
				                WHERE c.project_id = p.id AND c.status IN ('SCHEDULED', 'OPEN')
				                  AND c.reminder_at <= :now AND c.closes_at > :now
				                  AND NOT EXISTS (SELECT 1 FROM checkpoint_reports r
				                                   WHERE r.cycle_id = c.id AND r.member_id = :me AND r.status = 'SUBMITTED')) AS needs_checkpoint,
				       GREATEST(p.created_at,
				                (SELECT max(x.joined_at) FROM project_members x WHERE x.project_id = p.id),
				                (SELECT max(e.occurred_at) FROM notification_events e WHERE e.project_id = p.id)) AS last_activity
				  FROM project_members m JOIN projects p ON p.id = m.project_id
				 WHERE m.user_id = :me AND m.left_at IS NULL AND p.archived_at IS NULL
				 ORDER BY last_activity DESC NULLS LAST, p.name, p.id
				""")
				.param("me", user.id()).param("now", DbTime.toDb(now)).param("actionTypes", actionTypes)
				.query((rs, i) -> {
					ZoneId zone = ZoneId.of(rs.getString("timezone"));
					boolean checkpoint = rs.getBoolean("needs_checkpoint");
					long others = rs.getLong("other_actions");
					int count = (int) (others + (checkpoint ? 1 : 0));
					return new GroupCardDto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("course_name"),
							rs.getInt("member_count"), DbTime.instant(rs, "deadline").atZone(zone).toLocalDate(),
							rs.getString("role"), new GroupCardAttention(count, attentionLabel(count, checkpoint)),
							rs.getLong("unread"), ScheduleLabels.ms(DbTime.instant(rs, "last_activity")));
				}).list();
		return new ItemsResponse<>(items);
	}

	private static List<String> actionTypesExceptReminder() {
		List<String> names = new ArrayList<>();
		for (NotificationType t : NotificationType.values()) {
			if (t.priority() == NotificationType.Priority.ACTION_REQUIRED && t != NotificationType.CHECKPOINT_REMINDER) {
				names.add(t.name());
			}
		}
		return names;
	}

	/** "Không có việc cần chú ý" · "1 checkpoint cần phản hồi" · "Có N việc liên quan đến bạn" (spec S01). */
	private static String attentionLabel(int count, boolean checkpoint) {
		if (count == 0) {
			return "Không có việc cần chú ý";
		}
		if (count == 1 && checkpoint) {
			return "1 checkpoint cần phản hồi";
		}
		return "Có " + count + " việc liên quan đến bạn";
	}

	// ------------------------------------------------------------------ tạo nhóm (S02)
	@Transactional
	CreateGroupResponse create(CurrentUser user, CreateGroupRequest req) {
		if (!Boolean.TRUE.equals(req.scheduleAgreed())) {
			throw ApiException.validation("scheduleAgreed", "Cần xác nhận lịch đã được nhóm thống nhất.");
		}
		String name = req.name().trim();
		List<ApiError.FieldError> errors = new ArrayList<>();
		if (name.length() < 3 || name.length() > 80) {
			errors.add(new ApiError.FieldError("name", "Tên nhóm dài 3–80 ký tự."));
		}
		String course = req.courseName() == null || req.courseName().isBlank() ? null : req.courseName().trim();
		if (req.deadline().getYear() > MAX_YEAR || (req.startDate() != null && req.startDate().getYear() > MAX_YEAR)) {
			errors.add(new ApiError.FieldError("deadline", "Ngày không hợp lệ."));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		ScheduleSpec spec = schedules.parseSpec(req.schedule().weekday(), req.schedule().cutoffTime(),
				req.schedule().timezone(), req.schedule().reportingWindowMinutes(), "schedule.");
		Instant now = clock.instant();
		ZoneId zone = spec.zone();
		LocalDate today = LocalDate.ofInstant(now, zone);
		LocalDate startDate = req.startDate() != null ? req.startDate() : today;
		if (startDate.isBefore(today)) {
			throw ApiException.validation("startDate", "Ngày bắt đầu không được trước hôm nay.");
		}
		if (req.deadline().isBefore(startDate)) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_DEADLINE_INVALID",
					"Deadline của nhóm không được trước ngày bắt đầu.");
		}
		Instant startAt = startDate.atStartOfDay(zone).toInstant();
		UUID groupId = jdbc.sql("""
				INSERT INTO projects (name, course_name, start_at, deadline, timezone, created_by, created_at)
				VALUES (:name, :course, :start, :deadline, :tz, :by, :now) RETURNING id
				""")
				.param("name", name).param("course", course).param("start", DbTime.toDb(startAt))
				.param("deadline", DbTime.toDb(ScheduleService.endOfDay(req.deadline(), zone))).param("tz", zone.getId())
				.param("by", user.id()).param("now", DbTime.toDb(now)).query(UUID.class).single();
		jdbc.sql("INSERT INTO project_members (project_id, user_id, role, joined_at) VALUES (:p, :u, 'LEAD', :now)")
				.param("p", groupId).param("u", user.id()).param("now", DbTime.toDb(now)).update();
		InitialSchedule initial = schedules.createInitial(groupId, user.id(), spec, startAt, now);
		return new CreateGroupResponse(load(groupId, user.id()), initial.schedule(), initial.currentCycle(),
				invites.issue(groupId, user.id()));
	}

	// ------------------------------------------------------------------ sửa nhóm (TL)
	@Transactional
	GroupDto update(UUID groupId, CurrentUser user, JsonNode body) {
		access.requireLeader(groupId, user);
		List<ApiError.FieldError> errors = new ArrayList<>();
		if (body == null || !body.isObject()) {
			throw ApiException.validation("body", "Nội dung yêu cầu không hợp lệ.");
		}
		JsonNode versionNode = body.get("version");
		if (versionNode == null || !versionNode.isIntegralNumber()) {
			errors.add(new ApiError.FieldError("version", "Thiếu phiên bản (version)."));
		}
		String name = null;
		if (body.has("name") && !body.get("name").isNull()) {
			JsonNode n = body.get("name");
			name = n.isString() ? n.asString().trim() : null;
			if (name == null || name.length() < 3 || name.length() > 80) {
				errors.add(new ApiError.FieldError("name", "Tên nhóm dài 3–80 ký tự."));
			}
		}
		boolean courseGiven = body.has("courseName");
		String course = null;
		if (courseGiven && !body.get("courseName").isNull()) {
			JsonNode c = body.get("courseName");
			course = c.isString() && !c.asString().isBlank() ? c.asString().trim() : null;
			if (!c.isString() || (course != null && course.length() > 120)) {
				errors.add(new ApiError.FieldError("courseName", "Tên môn tối đa 120 ký tự."));
			}
		}
		LocalDate deadline = null;
		if (body.has("deadline") && !body.get("deadline").isNull()) {
			try {
				deadline = LocalDate.parse(body.get("deadline").asString());
				if (deadline.getYear() > MAX_YEAR) {
					throw new java.time.format.DateTimeParseException("year", "", 0);
				}
			}
			catch (java.time.format.DateTimeParseException ex) {
				errors.add(new ApiError.FieldError("deadline", "Deadline phải có dạng YYYY-MM-DD."));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		long version = versionNode.asLong();
		var current = jdbc.sql("SELECT start_at, timezone FROM projects WHERE id = :id FOR UPDATE").param("id", groupId)
				.query((rs, i) -> new Object[] { DbTime.instant(rs, "start_at"), rs.getString("timezone") }).single();
		ZoneId zone = ZoneId.of((String) current[1]);
		if (deadline != null && deadline.isBefore(((Instant) current[0]).atZone(zone).toLocalDate())) {
			throw ApiException.of(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_DEADLINE_INVALID",
					"Deadline của nhóm không được trước ngày bắt đầu.");
		}
		int updated = jdbc.sql("""
				UPDATE projects SET name = COALESCE(CAST(:name AS varchar), name),
				       course_name = CASE WHEN :courseGiven THEN CAST(:course AS varchar) ELSE course_name END,
				       deadline = COALESCE(CAST(:deadline AS timestamptz), deadline), version = version + 1
				 WHERE id = :id AND version = :version
				""")
				.param("name", name).param("courseGiven", courseGiven).param("course", course)
				.param("deadline", deadline == null ? null : DbTime.toDb(ScheduleService.endOfDay(deadline, zone)))
				.param("id", groupId).param("version", version).update();
		if (updated == 0) {
			throw ApiException.versionConflict();
		}
		return load(groupId, user.id());
	}

	// ------------------------------------------------------------------ thành viên
	@Transactional(readOnly = true)
	ItemsResponse<MemberDto> members(UUID groupId, CurrentUser user) {
		access.requireMember(groupId, user);
		List<MemberDto> items = jdbc.sql("""
				SELECT m.user_id, u.name, m.role, m.joined_at
				  FROM project_members m JOIN users u ON u.id = m.user_id
				 WHERE m.project_id = :p AND m.left_at IS NULL
				 ORDER BY (m.role = 'LEAD') DESC, m.joined_at, m.user_id
				""")
				.param("p", groupId)
				.query((rs, i) -> new MemberDto(rs.getObject("user_id", UUID.class), rs.getString("name"),
						rs.getString("role"), ScheduleLabels.ms(DbTime.instant(rs, "joined_at"))))
				.list();
		return new ItemsResponse<>(items);
	}
}
