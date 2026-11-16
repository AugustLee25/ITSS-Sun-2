package app.hanare.demo;

import app.hanare.common.ApiException;
import app.hanare.schedule.CheckpointJob;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mục L của hợp đồng: chỉ tồn tại ở profile {@code demo} (profile khác → 404). */
@RestController
@Profile("demo")
@RequestMapping("/api/v1/demo")
class DemoController {

	record DemoAccount(UUID userId, String name, String email, String password, String role, String note) {
	}

	record DemoAccountsResponse(UUID groupId, List<DemoAccount> items) {
	}

	record DemoClockResponse(Instant now, boolean fixed) {
	}

	record SetDemoClockRequest(Instant now, Integer advanceMinutes, Boolean runDueJobs) {
	}

	private final JdbcClient jdbc;
	private final DemoClock clock;
	private final CheckpointJob job;
	private final DemoDataLoader loader;

	DemoController(JdbcClient jdbc, DemoClock clock, CheckpointJob job, DemoDataLoader loader) {
		this.loader = loader;
		this.jdbc = jdbc;
		this.clock = clock;
		this.job = job;
	}

	@GetMapping("/accounts")
	DemoAccountsResponse accounts() {
		if (!loader.isReady()) {
			throw ApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "DEMO_LOADING", "Đang nạp dữ liệu mẫu, thử lại sau vài giây.");
		}
		List<DemoAccount> items = DemoAccounts.ALL.stream()
				.flatMap(a -> jdbc.sql("SELECT id FROM users WHERE email = :e").param("e", a.email()).query(UUID.class).optional()
						.map(id -> new DemoAccount(id, a.name(), a.email(), DemoAccounts.PASSWORD, a.role(), a.note())).stream())
				.toList();
		UUID groupId = jdbc.sql("SELECT id FROM projects WHERE name = :n ORDER BY created_at LIMIT 1")
				.param("n", DemoAccounts.GROUP_NAME).query(UUID.class).optional().orElse(null);
		return new DemoAccountsResponse(groupId, items);
	}

	@GetMapping("/clock")
	DemoClockResponse clock() {
		return view();
	}

	@PostMapping("/clock")
	DemoClockResponse setClock(@RequestBody SetDemoClockRequest req) {
		if ((req.now() == null) == (req.advanceMinutes() == null)) {
			throw ApiException.validation("now", "Cần đúng một trong now / advanceMinutes.");
		}
		if (req.advanceMinutes() != null && req.advanceMinutes() < 1) {
			throw ApiException.validation("advanceMinutes", "Phải ≥ 1.");
		}
		Instant target = req.now() != null ? req.now() : clock.instant().plus(req.advanceMinutes(), ChronoUnit.MINUTES);
		clock.set(target);
		if (req.runDueJobs() == null || req.runDueJobs()) {
			job.runOnce(target);
		}
		return view();
	}

	private DemoClockResponse view() {
		return new DemoClockResponse(clock.instant(), clock.isFixed());
	}
}
