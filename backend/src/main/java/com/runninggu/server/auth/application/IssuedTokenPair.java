package com.runninggu.server.auth.application;

import java.time.Instant;
import java.util.UUID;

public record IssuedTokenPair(
        UUID familyId,
        String accessToken,
        String refreshToken,
        Instant refreshExpiresAt) {}
