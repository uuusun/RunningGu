package com.runninggu.server.geocode.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.external.kakao-local")
public record KakaoLocalProperties(
        URI baseUrl,
        String restKey,
        Duration connectTimeout,
        Duration readTimeout) {}
