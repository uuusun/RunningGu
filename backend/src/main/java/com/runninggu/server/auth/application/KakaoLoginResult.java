package com.runninggu.server.auth.application;

public record KakaoLoginResult(
        AuthSessionResult session,
        KakaoUserProfile profile) {

    public static KakaoLoginResult existing(AuthSessionResult session) {
        return new KakaoLoginResult(session, null);
    }

    public static KakaoLoginResult signupRequired(KakaoUserProfile profile) {
        return new KakaoLoginResult(null, profile);
    }

    public boolean isNewUser() {
        return session == null;
    }
}
