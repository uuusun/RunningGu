package com.runninggu.server.auth.application;

public interface VerificationCodeHasher {
    String hash(String code);

    boolean matches(String code, String hash);
}
