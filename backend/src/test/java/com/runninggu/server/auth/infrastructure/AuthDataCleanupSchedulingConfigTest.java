package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.runninggu.server.course.application.CourseSyncService;
import com.runninggu.server.course.infrastructure.CourseSyncSchedulingConfig;
import java.lang.reflect.Method;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class AuthDataCleanupSchedulingConfigTest {

    @Test
    void 인증정리는_코스동기화와_다른_스케줄러를_명시해서_사용한다() throws Exception {
        Method cleanupHourly = AuthDataCleanupScheduler.class.getMethod("cleanupHourly");
        Scheduled scheduled = cleanupHourly.getAnnotation(Scheduled.class);

        assertThat(scheduled.scheduler()).isEqualTo("authDataCleanupTaskScheduler");
        new WebApplicationContextRunner()
                .withUserConfiguration(
                        AuthDataCleanupSchedulingConfig.class,
                        CourseSyncSchedulingConfig.class,
                        Dependencies.class)
                .withPropertyValues(
                        "runninggu.course.sync.enabled=true",
                        "runninggu.course.sync.interval=24h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskScheduler authScheduler = context.getBean(
                            "authDataCleanupTaskScheduler",
                            ThreadPoolTaskScheduler.class);
                    ThreadPoolTaskScheduler courseScheduler = context.getBean(
                            "courseSyncTaskScheduler",
                            ThreadPoolTaskScheduler.class);
                    assertThat(authScheduler).isNotSameAs(courseScheduler);
                    assertThat(authScheduler.getThreadNamePrefix()).isEqualTo("auth-cleanup-");
                    assertThat(courseScheduler.getThreadNamePrefix()).isEqualTo("course-sync-");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        CourseSyncService courseSyncService() {
            return mock(CourseSyncService.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
