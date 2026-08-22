package com.runninggu.server.auth.application;

public record AuthSessionResult(
        String accessToken,
        String refreshToken,
        AuthenticatedUser user) {}
