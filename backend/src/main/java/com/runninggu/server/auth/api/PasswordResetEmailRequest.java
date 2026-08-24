package com.runninggu.server.auth.api;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetEmailRequest(
        @NotBlank(message = "email 값이 필요합니다.") String email) {}
