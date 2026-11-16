package app.hanare.schedule;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật {@code @Scheduled} (job kỳ checkpoint). Tắt ở profile {@code test}: test gọi thẳng {@code CheckpointJob.runOnce(now)}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
class SchedulingConfig {
}
