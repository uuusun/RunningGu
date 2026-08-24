package com.runninggu.server.auth.api;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(
        @NotBlank(message = "token 값이 필요합니다.") String token,
        String newPassword) {}
