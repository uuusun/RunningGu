package com.runninggu.server.auth.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KakaoSignupRequest(
        @NotBlank String kakaoAccessToken,
        @NotBlank String nickname,
        @NotNull @Valid AgreementsRequest agreements) {}
