package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.runninggu.server.course.application.CourseSyncService;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CourseSyncSchedulingConfigTest {

    private final CourseSyncService syncService = mock(CourseSyncService.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CourseSyncSchedulingConfig.class)
            .withBean(CourseSyncService.class, () -> syncService)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void 동기화를_켜면_24시간_간격을_바인딩하고_서버_준비_직후_한번_실행한다() {
        contextRunner
                .withPropertyValues(
                        "runninggu.course.sync.enabled=true",
                        "runninggu.course.sync.interval=24h")
                .run(context -> {
                    assertThat(context).hasSingleBean(CourseSyncScheduler.class);
                    assertThat(context).hasSingleBean(CourseSyncProperties.class);
                    assertThat(context.getBean(CourseSyncProperties.class).interval())
                            .isEqualTo(Duration.ofHours(24));

                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(),
                            new String[0],
                            context.getSourceApplicationContext(),
                            Duration.ZERO));

                    verify(syncService, timeout(1_000).times(1)).synchronize();
                });
    }

    @Test
    void 동기화를_끄면_스케줄러_빈을_등록하지_않는다() {
        contextRunner
                .withPropertyValues("runninggu.course.sync.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CourseSyncScheduler.class);
                    assertThat(context).doesNotHaveBean(CourseSyncProperties.class);
                });
    }
}
