package com.runninggu.server.auth.application;

public interface PasswordHasher {
    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}
