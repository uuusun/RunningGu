package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.AuthenticatedUser;
import com.runninggu.server.auth.domain.LoginProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public record AuthUserResponse(
        long id,
        @Schema(nullable = true) String email,
        String nickname,
        LoginProvider loginProvider) {

    static AuthUserResponse from(AuthenticatedUser user) {
        return new AuthUserResponse(
                user.id(),
                user.email(),
                user.nickname(),
                user.loginProvider());
    }
}
