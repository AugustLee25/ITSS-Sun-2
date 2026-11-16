package app.hanare.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.hanare.group.ApiHelper;
import app.hanare.schedule.CheckpointJob.RunResult;
import app.hanare.testsupport.DbFixtures;
import app.hanare.testsupport.DbFixtures.UserRef;
import app.hanare.testsupport.FixedClock;
import app.hanare.testsupport.HanareTest;
import app.hanare.testsupport.TestAuth;
import app.hanare.testsupport.TestSupportConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Job kỳ với {@code FixedClock}: nhắc đúng giờ từng nhóm (12.10), chỉ MỘT lần, bù khi khởi động lại, đóng kỳ + gọi mọi
 * {@link CutoffHook} trong cùng transaction, chạy lặp/đồng thời không tạo trùng, luôn có sẵn kỳ tương lai.
 */
@HanareTest
@Import(CheckpointJobTest.Hooks.class)
class CheckpointJobTest {

	/** Hai hook để chứng minh MỌI bean CutoffHook đều được gọi; {@code failNext} giả lập hook lỗi. */
	static class RecordingHook implements CutoffHook {
		final List<CycleService.CycleRef> calls = Collections.synchronizedList(new ArrayList<>());
		volatile boolean failNext;

		@Override
		public void onCycleClosed(CycleService.CycleRef cycle) {
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("hook lỗi (giả lập)");
			}
			calls.add(cycle);
		}
	}

	static class CountingHook implements CutoffHook {
		final List<UUID> ids = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void onCycleClosed(CycleService.CycleRef cycle) {
			ids.add(cycle.id());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Hooks {
		@Bean
		RecordingHook recordingHook() {
			return new RecordingHook();
		}

		@Bean
		CountingHook countingHook() {
			return new CountingHook();
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	DbFixtures fx;
	@Autowired
	TestAuth auth;
	@Autowired
	FixedClock clock;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	CheckpointJob job;
	@Autowired
	RecordingHook recording;
	@Autowired
	CountingHook counting;

	@BeforeEach
	void reset() {
		fx.resetData();
		clock.set(Instant.parse(TestSupportConfig.DEFAULT_NOW));
		recording.calls.clear();
		recording.failNext = false;
		counting.ids.clear();
	}

	private record Group(UUID id, UserRef lead, List<UserRef> all) {
	}

	/** Nhóm tạo qua API với {@code size} người (TL + thành viên được thêm thẳng vào DB). */
	private Group group(String name, int weekday, String cutoff, int size) throws Exception {
		UserRef lead = fx.user("TL " + name);
		JsonNode created = ApiHelper.createGroup(mvc, auth, lead, name, weekday, cutoff);
		UUID gid = ApiHelper.id(created, "/group/id");
		List<UserRef> all = new ArrayList<>(List.of(lead));
		for (int i = 1; i < size; i++) {
			UserRef m = fx.user("Member" + i + " " + name);
			fx.addMember(gid, m, "MEMBER");
			all.add(m);
		}
		return new Group(gid, lead, all);
	}

	private List<UUID> remindedRecipients(UUID gid) {
		return jdbc.sql("""
				SELECT n.recipient_id FROM notifications n JOIN notification_events e ON e.id = n.event_id
				 WHERE e.project_id = :p AND e.event_type = 'CHECKPOINT_REMINDER'
				""").param("p", gid).query(UUID.class).list();
	}

	private int reminderEvents(UUID gid) {
		return jdbc.sql("SELECT count(*) FROM notification_events WHERE project_id = :p AND event_type = 'CHECKPOINT_REMINDER'")
				.param("p", gid).query(Integer.class).single();
	}

	private String cycleStatus(UUID gid, int seq) {
		return jdbc.sql("SELECT status FROM checkpoint_cycles WHERE project_id = :p AND sequence_no = :n")
				.param("p", gid).param("n", seq).query(String.class).single();
	}

	private static Instant at(String iso) {
		return Instant.parse(iso);
	}

	// ------------------------------------------------------------------ nhắc
	@Test
	void eachGroupIsRemindedAtItsOwnTimeToEveryActiveMemberIncludingLeadOnlyOnce() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 5); // chốt thứ Sáu 22:00 -> nhắc 15:00
		Group b = group("Nhóm B", 7, "20:00", 3); // chốt Chủ nhật 20:00 -> nhắc 13:00

		assertThat(job.runOnce(at("2026-11-20T07:59:59Z")).reminders()).isZero();
		assertThat(reminderEvents(a.id())).isZero();

		RunResult r = job.runOnce(at("2026-11-20T08:00:00Z")); // 15:00 thứ Sáu giờ VN
		assertThat(r.reminders()).isEqualTo(1);
		assertThat(remindedRecipients(a.id())).containsExactlyInAnyOrderElementsOf(a.all().stream().map(UserRef::id).toList());
		assertThat(remindedRecipients(a.id())).contains(a.lead().id()); // TL cũng nhận
		assertThat(reminderEvents(b.id())).isZero(); // nhóm B không phụ thuộc cron thứ Sáu

		// chạy lặp ở nhiều thời điểm trong [reminder_at, closes_at): không tạo thêm
		assertThat(job.runOnce(at("2026-11-20T08:00:00Z")).reminders()).isZero();
		assertThat(job.runOnce(at("2026-11-20T11:00:00Z")).reminders()).isZero();
		assertThat(job.runOnce(at("2026-11-20T14:59:59Z")).reminders()).isZero();
		assertThat(remindedRecipients(a.id())).hasSize(5);
		assertThat(reminderEvents(a.id())).isEqualTo(1);

		assertThat(job.runOnce(at("2026-11-22T05:59:59Z")).reminders()).isZero(); // CN 12:59:59 VN: chưa tới
		assertThat(reminderEvents(b.id())).isZero();
		assertThat(job.runOnce(at("2026-11-22T06:00:00Z")).reminders()).isEqualTo(1); // CN 13:00 VN
		assertThat(remindedRecipients(b.id())).containsExactlyInAnyOrderElementsOf(b.all().stream().map(UserRef::id).toList());
		assertThat(reminderEvents(a.id())).isEqualTo(1); // kỳ 2 của A chưa tới giờ nhắc (27/11)

		var row = jdbc.sql("""
				SELECT e.dedup_key, e.entity_type, n.title, n.message FROM notification_events e
				  JOIN notifications n ON n.event_id = e.id
				 WHERE e.project_id = :p AND e.event_type = 'CHECKPOINT_REMINDER' LIMIT 1
				""").param("p", a.id())
				.query((rs, i) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).single();
		UUID cycle1 = jdbc.sql("SELECT id FROM checkpoint_cycles WHERE project_id = :p AND sequence_no = 1")
				.param("p", a.id()).query(UUID.class).single();
		assertThat(row.get(0)).isEqualTo("checkpoint:" + cycle1 + ":reminder");
		assertThat(row.get(1)).isEqualTo("CHECKPOINT_CYCLE");
		assertThat(row.get(2)).isEqualTo("Đến lúc cập nhật ngắn về công việc rồi!");
		assertThat(row.get(3)).isEqualTo("Kỳ 1 chốt lúc 22:00 Thứ Sáu 20/11 (còn 7 giờ).");
	}

	@Test
	void membersWhoLeftDoNotGetTheReminder() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 4);
		UserRef gone = a.all().get(3);
		jdbc.sql("UPDATE project_members SET left_at = joined_at + interval '1 day' WHERE project_id = :p AND user_id = :u")
				.param("p", a.id()).param("u", gone.id()).update();
		job.runOnce(at("2026-11-20T08:00:00Z"));
		assertThat(remindedRecipients(a.id())).hasSize(3).doesNotContain(gone.id());
	}

	@Test
	void restartInsideReminderWindowCompensatesOnceButNeverAfterCutoff() throws Exception {
		Group c = group("Nhóm C", 5, "22:00", 2);
		Group d = group("Nhóm D", 5, "22:00", 2);
		// job "khởi động lại" lúc 19:00 VN (sau nhắc 15:00, trước chốt 22:00): bù đúng một lần
		assertThat(job.runOnce(at("2026-11-20T12:00:00Z")).reminders()).isEqualTo(2);
		assertThat(job.runOnce(at("2026-11-20T12:00:30Z")).reminders()).isZero();
		assertThat(reminderEvents(c.id())).isEqualTo(1);
		assertThat(remindedRecipients(c.id())).hasSize(2);

		// nhóm tạo sau đó, job chỉ chạy lại SAU giờ chốt -> không nhắc nữa, chỉ đóng kỳ
		clock.set(at("2026-11-20T13:00:00Z"));
		Group e = group("Nhóm E", 6, "23:00", 2); // chốt thứ Bảy 23:00 VN = 16:00Z hôm sau
		RunResult after = job.runOnce(at("2026-11-21T17:00:00Z"));
		assertThat(after.reminders()).isZero();
		assertThat(reminderEvents(e.id())).isZero();
		assertThat(cycleStatus(e.id(), 1)).isEqualTo("CLOSED");
		assertThat(cycleStatus(d.id(), 1)).isEqualTo("CLOSED");
	}

	// ------------------------------------------------------------------ mở / đóng
	@Test
	void narrowReportingWindowKeepsCycleScheduledUntilItsOpeningTime() throws Exception {
		UserRef lead = fx.user("TL cửa sổ");
		JsonNode created = ApiHelper.JSON.readTree(mvc.perform(post("/api/v1/groups")
				.header("Authorization", auth.bearer(lead)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"name":"Nhóm cửa sổ","deadline":"2026-12-20","scheduleAgreed":true,
						 "schedule":{"weekday":5,"cutoffTime":"22:00","timezone":"Asia/Ho_Chi_Minh","reportingWindowMinutes":1440}}
						""")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		UUID gid = ApiHelper.id(created, "/group/id");
		assertThat(created.at("/currentCycle/status").asString()).isEqualTo("SCHEDULED");
		assertThat(created.at("/currentCycle/reportWindowOpen").asBoolean()).isFalse();
		assertThat(created.at("/currentCycle/opensAt").asString()).isEqualTo("2026-11-19T15:00:00Z"); // đóng - 24h

		assertThat(job.runOnce(at("2026-11-19T14:59:59Z")).opened()).isZero();
		assertThat(cycleStatus(gid, 1)).isEqualTo("SCHEDULED");
		assertThat(job.runOnce(at("2026-11-19T15:00:00Z")).opened()).isEqualTo(1);
		assertThat(cycleStatus(gid, 1)).isEqualTo("OPEN");
		assertThat(job.runOnce(at("2026-11-19T15:00:00Z")).opened()).isZero();
	}

	@Test
	void closingAtCutoffRunsEveryHookOnceInTheSameTransactionAndOpensTheNextCycle() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 3);
		Group b = group("Nhóm B", 7, "20:00", 3);

		RunResult before = job.runOnce(at("2026-11-20T14:59:59.999Z"));
		assertThat(before.closed()).isZero();
		assertThat(cycleStatus(a.id(), 1)).isEqualTo("OPEN");
		assertThat(recording.calls).isEmpty();

		RunResult r = job.runOnce(at("2026-11-20T15:00:00Z")); // đúng 22:00 thứ Sáu giờ VN
		assertThat(r.closed()).isEqualTo(1); // chỉ nhóm A; nhóm B chốt Chủ nhật
		assertThat(r.opened()).isEqualTo(1); // kỳ 2 của A mở ngay
		assertThat(cycleStatus(a.id(), 1)).isEqualTo("CLOSED");
		assertThat(cycleStatus(a.id(), 2)).isEqualTo("OPEN");
		assertThat(cycleStatus(b.id(), 1)).isEqualTo("OPEN");
		assertThat(jdbc.sql("SELECT closed_at FROM checkpoint_cycles WHERE project_id = :p AND sequence_no = 1")
				.param("p", a.id()).query((rs, i) -> app.hanare.common.DbTime.instant(rs, "closed_at")).single())
				.isEqualTo(at("2026-11-20T15:00:00Z"));

		UUID aCycle1 = jdbc.sql("SELECT id FROM checkpoint_cycles WHERE project_id = :p AND sequence_no = 1")
				.param("p", a.id()).query(UUID.class).single();
		assertThat(recording.calls).hasSize(1);
		CycleService.CycleRef ref = recording.calls.get(0);
		assertThat(ref.id()).isEqualTo(aCycle1);
		assertThat(ref.projectId()).isEqualTo(a.id());
		assertThat(ref.status()).isEqualTo("CLOSED");
		assertThat(ref.closedAt()).isEqualTo(at("2026-11-20T15:00:00Z"));
		assertThat(ref.closesAt()).isEqualTo(at("2026-11-20T15:00:00Z"));
		assertThat(counting.ids).containsExactly(aCycle1); // hook thứ hai cũng được gọi

		// chạy lại: không đóng/không gọi hook lần hai
		assertThat(job.runOnce(at("2026-11-20T15:00:00Z")).closed()).isZero();
		assertThat(job.runOnce(at("2026-11-20T18:00:00Z")).closed()).isZero();
		assertThat(recording.calls).hasSize(1);

		// nhóm B chốt Chủ nhật 20:00 giờ VN, độc lập
		assertThat(job.runOnce(at("2026-11-22T13:00:00Z")).closed()).isEqualTo(1);
		assertThat(cycleStatus(b.id(), 1)).isEqualTo("CLOSED");
		assertThat(recording.calls).hasSize(2);
		assertThat(recording.calls.get(1).projectId()).isEqualTo(b.id());
	}

	@Test
	void aFailingHookRollsTheCloseBackAndTheNextRunRetries() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 2);
		recording.failNext = true;
		RunResult first = job.runOnce(at("2026-11-20T15:00:00Z"));
		assertThat(first.closed()).isZero();
		assertThat(cycleStatus(a.id(), 1)).isEqualTo("OPEN"); // transaction được hoàn tác: kỳ chưa đóng
		assertThat(jdbc.sql("SELECT closed_at IS NULL FROM checkpoint_cycles WHERE project_id = :p AND sequence_no = 1")
				.param("p", a.id()).query(Boolean.class).single()).isTrue();
		assertThat(recording.calls).isEmpty();

		assertThat(job.runOnce(at("2026-11-20T15:00:30Z")).closed()).isEqualTo(1);
		assertThat(cycleStatus(a.id(), 1)).isEqualTo("CLOSED");
		assertThat(recording.calls).hasSize(1);
	}

	// ------------------------------------------------------------------ đồng thời
	@Test
	void concurrentRunsNeverDuplicateRemindersOrCloses() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 5);
		assertThat(concurrently(3, at("2026-11-20T08:00:00Z")).stream().mapToInt(RunResult::reminders).sum()).isEqualTo(1);
		assertThat(reminderEvents(a.id())).isEqualTo(1);
		assertThat(remindedRecipients(a.id())).hasSize(5);

		assertThat(concurrently(3, at("2026-11-20T15:00:00Z")).stream().mapToInt(RunResult::closed).sum()).isEqualTo(1);
		assertThat(recording.calls).hasSize(1);
		assertThat(counting.ids).hasSize(1);
		assertThat(cycleStatus(a.id(), 1)).isEqualTo("CLOSED");
		assertThat(cycleStatus(a.id(), 2)).isEqualTo("OPEN");
		// không sinh trùng kỳ: số thứ tự liên tục 1..n, mỗi kỳ có đúng một kỳ kế tiếp
		assertThat(jdbc.sql("SELECT count(*) = max(sequence_no) FROM checkpoint_cycles WHERE project_id = :p")
				.param("p", a.id()).query(Boolean.class).single()).isTrue();
	}

	private List<RunResult> concurrently(int threads, Instant now) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<RunResult>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<RunResult> task = () -> {
					go.await();
					return job.runOnce(now);
				};
				futures.add(pool.submit(task));
			}
			go.countDown();
			List<RunResult> results = new ArrayList<>();
			for (Future<RunResult> f : futures) {
				results.add(f.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ------------------------------------------------------------------ sinh kỳ
	@Test
	void alwaysKeepsFiveUnfinishedCyclesAndFillsGapsAfterLongDowntime() throws Exception {
		Group a = group("Nhóm A", 5, "22:00", 2);
		// 19/12: cả 5 kỳ ban đầu của A đã qua -> đóng hết và sinh thêm 5 kỳ tương lai (kỳ 6..10)
		RunResult r = job.runOnce(at("2026-12-19T00:00:00Z"));
		assertThat(r.closed()).isEqualTo(5);
		assertThat(r.generatedCycles()).isEqualTo(5);
		assertThat(futureCycles(a.id(), at("2026-12-19T00:00:00Z"))).isEqualTo(5);
		assertThat(cycleStatus(a.id(), 10)).isEqualTo("SCHEDULED");
		assertThat(job.runOnce(at("2026-12-19T00:00:00Z")).generatedCycles()).isZero();

		// ngừng job nhiều tuần: 5 kỳ bỏ lỡ (kỳ 11-15) + 5 kỳ tương lai được sinh nối liền, kỳ bỏ lỡ đóng ở lần chạy sau
		Instant later = at("2027-03-01T00:00:00Z");
		RunResult gap = job.runOnce(later);
		assertThat(gap.generatedCycles()).isEqualTo(10);
		assertThat(futureCycles(a.id(), later)).isEqualTo(5);
		assertThat(job.runOnce(later).closed()).isEqualTo(5);
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_cycles WHERE project_id = :p AND closes_at <= :now AND status <> 'CLOSED'")
				.param("p", a.id()).param("now", app.hanare.common.DbTime.toDb(later)).query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("""
				SELECT count(*) FROM checkpoint_cycles c JOIN checkpoint_cycles p ON p.id = c.previous_cycle_id
				 WHERE c.project_id = :p AND c.period_start_at <> p.closes_at
				""").param("p", a.id()).query(Integer.class).single()).isZero();
		// mỗi kỳ đúng 7 ngày (cùng lịch) sau kỳ 5
		assertThat(jdbc.sql("""
				SELECT count(*) FROM checkpoint_cycles WHERE project_id = :p AND sequence_no > 5
				   AND closes_at - period_start_at <> interval '7 days'
				""").param("p", a.id()).query(Integer.class).single()).isZero();
	}

	private int futureCycles(UUID gid, Instant now) {
		return jdbc.sql("SELECT count(*) FROM checkpoint_cycles WHERE project_id = :p AND closes_at > :now AND status <> 'CANCELLED'")
				.param("p", gid).param("now", app.hanare.common.DbTime.toDb(now)).query(Integer.class).single();
	}

	@Test
	void fixtureGroupsWithoutAScheduleAreIgnored() {
		DbFixtures.GroupRef g = fx.group("Không lịch", 3);
		RunResult r = job.runOnce(at("2026-11-20T08:00:00Z"));
		assertThat(r.generatedCycles()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM checkpoint_cycles WHERE project_id = :p").param("p", g.projectId())
				.query(Integer.class).single()).isZero();
	}
}
