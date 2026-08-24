package com.runninggu.server.auth.application;

public interface KakaoUserInfoProvider {
    KakaoUserProfile retrieve(String accessToken);
}
