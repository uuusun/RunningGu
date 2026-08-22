package com.runninggu.server.auth.application;

import java.time.Instant;

public record DecodedRefreshToken(
        long userId,
        String tokenId,
        Instant issuedAt,
        Instant expiresAt) {}
