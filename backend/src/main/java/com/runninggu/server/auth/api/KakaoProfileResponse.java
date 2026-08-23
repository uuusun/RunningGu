package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.KakaoUserProfile;
import io.swagger.v3.oas.annotations.media.Schema;

public record KakaoProfileResponse(
        @Schema(nullable = true) String nickname,
        @Schema(nullable = true) String email) {

    static KakaoProfileResponse from(KakaoUserProfile profile) {
        return new KakaoProfileResponse(profile.nickname(), profile.email());
    }
}
