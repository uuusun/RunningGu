package com.runninggu.server.auth.application;

public interface PasswordResetTokenManager {

    String generate();

    String hash(String rawToken);
}
