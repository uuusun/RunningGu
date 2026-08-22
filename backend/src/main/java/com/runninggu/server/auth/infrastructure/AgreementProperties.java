package com.runninggu.server.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("runninggu.auth.agreements")
public record AgreementProperties(
        String tosVersion,
        String privacyVersion,
        String marketingVersion) {}
