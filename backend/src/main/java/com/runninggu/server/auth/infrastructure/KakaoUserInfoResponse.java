package com.runninggu.server.auth.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoUserInfoResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAccount(
            Profile profile,
            String email) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(String nickname) {}
}
