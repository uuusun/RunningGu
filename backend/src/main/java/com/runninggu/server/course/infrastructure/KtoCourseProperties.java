package com.runninggu.server.course.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.external.kto-course")
public record KtoCourseProperties(
        URI baseUrl,
        String serviceKey,
        Duration connectTimeout,
        Duration readTimeout) {}
