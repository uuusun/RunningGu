package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.AuthSessionResult;

public record AuthSessionResponse(
        String accessToken,
        String refreshToken,
        AuthUserResponse user) {

    static AuthSessionResponse from(AuthSessionResult result) {
        return new AuthSessionResponse(
                result.accessToken(),
                result.refreshToken(),
                AuthUserResponse.from(result.user()));
    }
}
