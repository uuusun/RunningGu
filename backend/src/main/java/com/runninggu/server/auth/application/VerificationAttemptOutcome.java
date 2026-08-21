package com.runninggu.server.auth.application;

enum VerificationAttemptOutcome {
    VERIFIED,
    INVALID_CODE,
    CODE_EXPIRED,
    TOO_MANY_ATTEMPTS
}
