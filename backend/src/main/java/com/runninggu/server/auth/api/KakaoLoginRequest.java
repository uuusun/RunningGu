package com.runninggu.server.auth.api;

import jakarta.validation.constraints.NotBlank;

public record KakaoLoginRequest(@NotBlank String kakaoAccessToken) {}
