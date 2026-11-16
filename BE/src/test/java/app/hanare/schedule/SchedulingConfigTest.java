package app.hanare.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import app.hanare.notification.NotificationService;
import app.hanare.security.GroupAccess;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code @Scheduled} của {@link CheckpointJob} phải khởi động được ở môi trường thật (không có profile test) và KHÔNG
 * được đăng ký ở profile {@code test}. Không cần DB: các phụ thuộc là mock.
 */
class SchedulingConfigTest {

	private AnnotationConfigApplicationContext context(String... profiles) {
		AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
		StandardEnvironment env = new StandardEnvironment();
		env.setActiveProfiles(profiles);
		ctx.setEnvironment(env);
		ctx.register(SchedulingConfig.class);
		ctx.registerBean(JdbcClient.class, () -> mock(JdbcClient.class));
		ctx.registerBean(CycleRepository.class, () -> mock(CycleRepository.class));
		ctx.registerBean(ScheduleService.class, () -> mock(ScheduleService.class));
		ctx.registerBean(NotificationService.class, () -> mock(NotificationService.class));
		ctx.registerBean(GroupAccess.class, () -> mock(GroupAccess.class));
		ctx.registerBean(Clock.class, Clock::systemUTC);
		ctx.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
		ctx.registerBean(CheckpointJob.class);
		ctx.refresh();
		return ctx;
	}

	@Test
	void jobIsScheduledEvery30SecondsOutsideTheTestProfile() {
		try (AnnotationConfigApplicationContext ctx = context()) {
			var tasks = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
			assertThat(tasks).hasSize(1);
			FixedDelayTask task = (FixedDelayTask) tasks.iterator().next().getTask();
			assertThat(task.getIntervalDuration().toSeconds()).isEqualTo(30);
			assertThat(task.getInitialDelayDuration().toSeconds()).isEqualTo(15);
		}
	}

	@Test
	void jobIsNotScheduledInTheTestProfile() {
		try (AnnotationConfigApplicationContext ctx = context("test")) {
			assertThat(ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
			assertThat(ctx.getBean(CheckpointJob.class)).isNotNull(); // bean vẫn có để test gọi runOnce
		}
	}
}
