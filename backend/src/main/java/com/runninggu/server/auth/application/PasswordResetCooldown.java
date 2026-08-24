package com.runninggu.server.auth.application;

public interface PasswordResetCooldown {

    void acquire(String normalizedEmail);

    void release(String normalizedEmail);
}
