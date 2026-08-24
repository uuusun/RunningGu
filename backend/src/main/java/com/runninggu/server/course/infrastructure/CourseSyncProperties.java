package com.runninggu.server.course.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.course.sync")
public record CourseSyncProperties(
        boolean enabled,
        Duration interval) {}
