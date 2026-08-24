package com.runninggu.server.auth.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KakaoAuthService {

    private final KakaoUserInfoProvider userInfoProvider;
    private final KakaoAuthTransaction transaction;

    public KakaoAuthService(
            KakaoUserInfoProvider userInfoProvider,
            KakaoAuthTransaction transaction) {
        this.userInfoProvider = userInfoProvider;
        this.transaction = transaction;
    }

    /** 외부 토큰 검증을 DB 트랜잭션 밖에서 끝낸다. (SPEC §4.1, API 명세 §1-7) */
    public KakaoLoginResult login(String kakaoAccessToken) {
        return transaction.login(retrieve(kakaoAccessToken));
    }

    /** 첫 판별 결과를 신뢰하지 않고 가입 시점에 카카오 토큰을 다시 검증한다. (SPEC §4.2) */
    public AuthSessionResult signup(
            String kakaoAccessToken,
            String nickname,
            boolean tos,
            boolean privacy,
            boolean marketing) {
        KakaoUserProfile profile = retrieve(kakaoAccessToken);
        return transaction.signup(profile, nickname, tos, privacy, marketing);
    }

    private KakaoUserProfile retrieve(String accessToken) {
        try {
            return userInfoProvider.retrieve(accessToken);
        } catch (KakaoUserInfoException exception) {
            throw switch (exception.reason()) {
                case INVALID_TOKEN -> new ApiException(
                        ErrorCode.INVALID_KAKAO_TOKEN,
                        "카카오 액세스 토큰이 올바르지 않습니다.");
                case TIMEOUT -> new ApiException(
                        ErrorCode.EXTERNAL_API_TIMEOUT,
                        "카카오 사용자 정보 응답 시간이 초과됐습니다.");
                case ERROR -> new ApiException(
                        ErrorCode.EXTERNAL_API_ERROR,
                        "카카오 사용자 정보를 확인하지 못했습니다.");
            };
        }
    }
}
