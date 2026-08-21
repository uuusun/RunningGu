package com.runninggu.server.poi.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.external.kto-poi")
public record KtoPoiProperties(
        URI baseUrl,
        URI wellnessBaseUrl,
        String serviceKey,
        Duration connectTimeout,
        Duration readTimeout) {}
