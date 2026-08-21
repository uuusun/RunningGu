package com.runninggu.server.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
        @NotBlank String email,
        @NotBlank String password,
        @NotBlank String nickname,
        @NotNull @Valid AgreementsRequest agreements) {}
