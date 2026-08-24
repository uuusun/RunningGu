package com.runninggu.server.member.api;

import com.runninggu.server.auth.domain.LoginProvider;
import jakarta.validation.constraints.NotNull;

public record ReauthRequest(
        @NotNull LoginProvider provider,
        String password,
        String kakaoAccessToken) {}
