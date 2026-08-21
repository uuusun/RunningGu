package com.runninggu.server.auth.api;

import jakarta.validation.constraints.NotBlank;

public record SendCodeRequest(
        @NotBlank(message = "email 값이 필요합니다.") String email) {}
