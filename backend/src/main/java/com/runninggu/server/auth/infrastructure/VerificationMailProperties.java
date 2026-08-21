package com.runninggu.server.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runninggu.mail")
public record VerificationMailProperties(
        boolean enabled,
        String fromAddress,
        String fromName) {}
