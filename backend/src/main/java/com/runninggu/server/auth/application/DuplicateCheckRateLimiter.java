package com.runninggu.server.auth.application;

public interface DuplicateCheckRateLimiter {
    void check(String clientIp, String targetType, String normalizedTarget);
}
