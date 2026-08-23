package com.runninggu.server.auth.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.external.kakao-user-info")
public record KakaoUserInfoProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {}
