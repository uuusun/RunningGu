package com.runninggu.server.auth.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("runninggu.auth.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        String audience,
        Duration accessTtl,
        Duration refreshTtl) {}
