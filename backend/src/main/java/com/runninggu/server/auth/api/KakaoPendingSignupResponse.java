package com.runninggu.server.auth.api;

import com.runninggu.server.auth.application.KakaoUserProfile;

public record KakaoPendingSignupResponse(
        boolean isNewUser,
        KakaoProfileResponse kakaoProfile) implements KakaoLoginResponse {

    static KakaoPendingSignupResponse from(KakaoUserProfile profile) {
        return new KakaoPendingSignupResponse(true, KakaoProfileResponse.from(profile));
    }
}
