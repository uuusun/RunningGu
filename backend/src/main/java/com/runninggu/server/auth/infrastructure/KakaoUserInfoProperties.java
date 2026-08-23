package com.runninggu.server.auth.infrastructure;

import java.net.URI;
import java.time.Duration;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "runninggu.external.kakao-user-info")
public record KakaoUserInfoProperties(
        URI baseUrl,
        @Positive long appId,
        Duration connectTimeout,
        Duration readTimeout) {}
