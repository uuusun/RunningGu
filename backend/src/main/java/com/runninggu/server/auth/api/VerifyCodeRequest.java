package com.runninggu.server.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyCodeRequest(
        @NotBlank(message = "email 값이 필요합니다.") String email,
        @NotBlank(message = "code 값이 필요합니다.")
                @Pattern(regexp = "^[0-9]{6}$", message = "code 값은 6자리 숫자여야 합니다.")
                String code) {}
