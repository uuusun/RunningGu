package com.runninggu.server.auth.application;

/** 이메일 로그인 공격 방어 경계다. (SPEC §4.1, 결정-55) */
public interface LoginAttemptRateLimiter {

    void checkIp(String clientIp);

    void checkEmail(String normalizedEmail);

    void resetEmail(String normalizedEmail);
}
