package com.runninggu.server.course.infrastructure;

import com.runninggu.server.course.application.CourseSyncService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "runninggu.course.sync",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(CourseSyncProperties.class)
public class CourseSyncSchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler courseSyncTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("course-sync-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    public CourseSyncScheduler courseSyncScheduler(
            @Qualifier("courseSyncTaskScheduler")
            ThreadPoolTaskScheduler courseSyncTaskScheduler,
            CourseSyncService syncService,
            CourseSyncProperties properties,
            Clock clock) {
        return new CourseSyncScheduler(
                courseSyncTaskScheduler,
                syncService,
                properties,
                clock);
    }
}
