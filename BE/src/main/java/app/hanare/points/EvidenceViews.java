package app.hanare.points;

import app.hanare.common.DbTime;
import app.hanare.points.PointDtos.ContributionDto;
import app.hanare.points.PointDtos.EvidenceItem;
import app.hanare.points.PointDtos.ReviewDto;
import app.hanare.points.PointDtos.TaskSubmissionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Dựng DTO lần nộp / quyết định / contribution (đọc SQL) cho S10/S11/S12. */
@Component
public class EvidenceViews {

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final Lookups lookups;

	public EvidenceViews(JdbcClient jdbc, JsonMapper json, Lookups lookups) {
		this.jdbc = jdbc;
		this.json = json;
		this.lookups = lookups;
	}

	public List<EvidenceItem> evidence(String jsonText) {
		List<EvidenceItem> out = new ArrayList<>();
		if (jsonText == null) {
			return out;
		}
		List<Map<String, Object>> raw = json.readValue(jsonText, new TypeReference<List<Map<String, Object>>>() {
		});
		for (Map<String, Object> m : raw) {
			out.add(new EvidenceItem(String.valueOf(m.getOrDefault("type", "URL")), String.valueOf(m.get("url")),
					m.get("label") == null ? null : String.valueOf(m.get("label"))));
		}
		return out;
	}

	public String toJson(List<EvidenceItem> items) {
		return json.writeValueAsString(items.stream()
				.map(e -> {
					Map<String, Object> m = new java.util.LinkedHashMap<>();
					m.put("type", e.type() == null ? "URL" : e.type());
					m.put("url", e.url());
					if (e.label() != null) {
						m.put("label", e.label());
					}
					return m;
				}).toList());
	}

	public Optional<TaskSubmissionDto> submission(UUID submissionId) {
		return jdbc.sql("""
				SELECT s.id, s.round_id, r.task_id, s.plan_revision_id, s.submission_no, s.submitted_by, s.result_note,
				       s.evidence::text ev, s.submitted_at, p.deadline,
				       (s.submission_no = (SELECT MAX(x.submission_no) FROM task_submissions x WHERE x.round_id = s.round_id)) latest,
				       rv.id rv_id, rv.decision, rv.reviewer_id, rv.issue_description, rv.reviewed_at,
				       (SELECT ss.id FROM support_submissions ss WHERE ss.task_submission_id = s.id) support_submission_id
				  FROM task_submissions s
				  JOIN task_review_rounds r ON r.id = s.round_id
				  JOIN task_plan_revisions p ON p.id = s.plan_revision_id
				  LEFT JOIN task_result_reviews rv ON rv.submission_id = s.id
				 WHERE s.id = :id
				""").param("id", submissionId).query((rs, i) -> {
					ReviewDto review = rs.getObject("rv_id") == null ? null
							: new ReviewDto(rs.getObject("rv_id", UUID.class), rs.getObject("id", UUID.class),
									rs.getString("decision"), lookups.user(rs.getObject("reviewer_id", UUID.class)),
									rs.getString("issue_description"), DbTime.instant(rs, "reviewed_at"));
					java.time.Instant submittedAt = DbTime.instant(rs, "submitted_at");
					java.time.Instant deadline = DbTime.instant(rs, "deadline");
					return new TaskSubmissionDto(rs.getObject("id", UUID.class), rs.getObject("round_id", UUID.class),
							rs.getObject("task_id", UUID.class), rs.getObject("plan_revision_id", UUID.class),
							rs.getInt("submission_no"), lookups.user(rs.getObject("submitted_by", UUID.class)),
							rs.getString("result_note"), evidence(rs.getString("ev")), submittedAt, deadline,
							deadline == null || !submittedAt.isAfter(deadline), rs.getBoolean("latest"), review,
							rs.getObject("support_submission_id", UUID.class));
				}).optional();
	}

	public Optional<ContributionDto> contribution(UUID contributionId) {
		return jdbc.sql("""
				SELECT c.id, c.work_id, c.helper_id, c.status, c.accepted_submission_id, c.confirmed_by, c.confirmed_at,
				       (SELECT l.id FROM point_ledger_entries l JOIN task_point_settlements s ON s.id = l.settlement_id
				         WHERE s.contribution_id = c.id AND l.entry_type = 'AWARD' AND s.revoked_at IS NULL LIMIT 1) ledger_id
				  FROM contributions c WHERE c.id = :id
				""").param("id", contributionId)
				.query((rs, i) -> new ContributionDto(rs.getObject("id", UUID.class), rs.getObject("work_id", UUID.class),
						lookups.user(rs.getObject("helper_id", UUID.class)), rs.getString("status"),
						rs.getObject("accepted_submission_id", UUID.class),
						lookups.user(rs.getObject("confirmed_by", UUID.class)), DbTime.instant(rs, "confirmed_at"),
						rs.getObject("ledger_id", UUID.class)))
				.optional();
	}
}
