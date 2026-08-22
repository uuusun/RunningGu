package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.LoginProvider;

public record AuthenticatedUser(
        long id,
        String email,
        String nickname,
        LoginProvider loginProvider) {}
