package com.runninggu.server.festival.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.external.kto-festival")
public record KtoFestivalProperties(
        URI baseUrl,
        String serviceKey,
        Duration connectTimeout,
        Duration readTimeout) {}
