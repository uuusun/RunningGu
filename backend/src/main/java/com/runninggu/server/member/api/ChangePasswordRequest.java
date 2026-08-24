package com.runninggu.server.member.api;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank String newPassword) {}
