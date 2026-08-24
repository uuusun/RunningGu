package com.runninggu.server.member.application;

import java.time.Instant;

public interface ReauthTokenManager {

    IssuedReauthToken issue(long userId, Instant issuedAt);

    long decodeUserId(String rawToken);
}
