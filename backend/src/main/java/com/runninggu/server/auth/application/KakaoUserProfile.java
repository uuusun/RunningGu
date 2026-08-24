package com.runninggu.server.auth.application;

/** 카카오 사용자 정보 API에서 검증한 가입 주체와 nullable 프로필 snapshot이다. */
public record KakaoUserProfile(
        String subject,
        String nickname,
        String email) {}
