package com.runninggu.server.auth.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KakaoSignupRequest(
        @NotBlank String kakaoAccessToken,
        @NotBlank String nickname,
        @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean ageOver14,
        @NotNull @Valid AgreementsRequest agreements) {}
