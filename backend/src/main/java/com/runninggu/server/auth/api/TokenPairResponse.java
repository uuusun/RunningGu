package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.TokenPair;

public record TokenPairResponse(String accessToken, String refreshToken) {
    public static TokenPairResponse from(TokenPair tokens) {
        return new TokenPairResponse(tokens.accessToken(), tokens.refreshToken());
    }
}
