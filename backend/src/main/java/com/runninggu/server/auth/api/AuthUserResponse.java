package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.AuthenticatedUser;
import com.runninggu.server.auth.domain.LoginProvider;

public record AuthUserResponse(
        long id,
        String email,
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
