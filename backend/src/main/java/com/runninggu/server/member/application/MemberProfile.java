package com.runninggu.server.member.application;

import com.runninggu.server.auth.domain.LoginProvider;
import java.time.Instant;

public record MemberProfile(
        long id,
        String email,
        String nickname,
        LoginProvider loginProvider,
        Agreements agreements,
        Instant createdAt) {

    public record Agreements(
            boolean tos,
            boolean privacy,
            boolean marketing) {}
}
