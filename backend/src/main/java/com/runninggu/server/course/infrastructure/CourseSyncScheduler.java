package com.runninggu.server.course.infrastructure;

import com.runninggu.server.course.application.CourseSyncService;
import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 서버 준비 뒤 즉시 시작하고, 완료 시점 기준 24시간 고정 지연으로 다시 동기화한다. */
public final class CourseSyncScheduler {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final CourseSyncService syncService;
    private final CourseSyncProperties properties;
    private final Clock clock;

    public CourseSyncScheduler(
            ThreadPoolTaskScheduler taskScheduler,
            CourseSyncService syncService,
            CourseSyncProperties properties,
            Clock clock) {
        this.taskScheduler = taskScheduler;
        this.syncService = syncService;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        taskScheduler.scheduleWithFixedDelay(
                syncService::synchronize,
                clock.instant(),
                properties.interval());
    }
}
