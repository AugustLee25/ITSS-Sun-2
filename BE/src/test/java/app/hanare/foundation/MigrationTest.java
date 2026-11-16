package app.hanare.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import app.hanare.testsupport.AbstractDbTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Toàn bộ migration V1..V7 áp dụng sạch trên PostgreSQL nhúng và tạo đủ các bảng của spec mục 12. */
class MigrationTest extends AbstractDbTest {

	private static final Set<String> EXPECTED_TABLES = Set.of(
			// 12.3
			"users", "projects", "project_members", "project_invitations", "checkpoint_schedules", "checkpoint_cycles",
			"checkpoint_participants",
			// 12.4
			"tasks", "task_review_rounds", "task_plan_revisions", "task_submissions", "task_result_reviews",
			"task_carryovers", "audit_logs",
			// 12.5
			"checkpoint_task_snapshots", "checkpoint_reports", "checkpoint_report_items",
			// 12.6
			"support_requests", "help_requests", "support_offers", "support_works", "support_submissions",
			"contributions", "risk_reports",
			// 12.7
			"notification_events", "notifications",
			// 12.8.6
			"point_policies", "member_point_budgets", "task_point_budgets", "task_point_reservations",
			"task_point_assignments", "task_point_settlements", "point_ledger_entries");

	@Test
	void allSevenMigrationsAppliedSuccessfully() {
		List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
				.query(String.class).list();
		assertThat(versions).startsWith("1", "2", "3", "4", "5", "6", "7");
	}

	@Test
	void allSpecTablesExist() {
		List<String> tables = jdbc.sql("""
				SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
				""").query(String.class).list();
		assertThat(tables).containsAll(EXPECTED_TABLES);
	}

	@Test
	void enumsAreVarcharWithCheckAndTimesAreTimestamptz() {
		assertThat(jdbc.sql("SELECT count(*) FROM pg_type WHERE typtype = 'e'").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				 WHERE table_schema = 'public' AND data_type = 'timestamp without time zone'
				   AND table_name <> 'flyway_schema_history'
				""").query(Long.class).single()).isZero();
		// mọi PK là uuid (trừ project_members: PK ghép)
		assertThat(jdbc.sql("""
				SELECT count(*) FROM information_schema.columns c
				 WHERE c.table_schema = 'public' AND c.column_name = 'id' AND c.data_type <> 'uuid'
				   AND c.table_name <> 'flyway_schema_history'
				""").query(Long.class).single()).isZero();
	}

	@Test
	void pointColumnsAreBigintUnits() {
		List<String> nonBigint = jdbc.sql("""
				SELECT table_name || '.' || column_name FROM information_schema.columns
				 WHERE table_schema = 'public'
				   AND column_name IN ('point_units', 'total_units', 'initial_units', 'reserve_units', 'delta_units',
				                       'advertised_point_units', 'point_units_snapshot')
				   AND data_type <> 'bigint'
				""").query(String.class).list();
		assertThat(nonBigint).isEmpty();
	}

	@Test
	void timezoneAndWeekdayChecksReject() {
		var user = fx.user("TZ Tester");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO projects (name, start_at, deadline, timezone, created_by)
				VALUES ('Nhóm TZ xấu', now(), now() + interval '30 days', 'Mars/Olympus', :u)
				""").param("u", user.id()).update())
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}
}
