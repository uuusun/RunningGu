package com.runninggu.server.auth.application;

public interface VerificationMailSender {
    void sendSignupCode(String recipient, String code);

    void sendPasswordResetLink(String recipient, String rawToken);
}
