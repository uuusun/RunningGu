package com.runninggu.server.member.application;

import com.runninggu.server.auth.application.KakaoUserInfoException;
import com.runninggu.server.auth.application.KakaoUserInfoProvider;
import com.runninggu.server.auth.application.KakaoUserProfile;
import com.runninggu.server.auth.application.PasswordHasher;
import com.runninggu.server.auth.application.PasswordPolicy;
import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemberReauthService {

    private final LoginIdentityRepository loginIdentityRepository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final KakaoUserInfoProvider kakaoUserInfoProvider;
    private final ReauthTokenManager reauthTokenManager;
    private final Clock clock;

    public MemberReauthService(
            LoginIdentityRepository loginIdentityRepository,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            KakaoUserInfoProvider kakaoUserInfoProvider,
            ReauthTokenManager reauthTokenManager,
            Clock clock) {
        this.loginIdentityRepository = loginIdentityRepository;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.kakaoUserInfoProvider = kakaoUserInfoProvider;
        this.reauthTokenManager = reauthTokenManager;
        this.clock = clock;
    }

    private LoginIdentity requireIdentity(long userId) {
        return loginIdentityRepository.findByUser_Id(userId)
                .orElseThrow(this::unauthorized);
    }

    public IssuedReauthToken reauthenticate(
            long userId,
            LoginProvider requestedProvider,
            String password,
            String kakaoAccessToken) {
        LoginIdentity identity = requireIdentity(userId);
        if (identity.getProvider() != requestedProvider) {
            throw new ApiException(
                    ErrorCode.REAUTH_PROVIDER_MISMATCH,
                    "가입한 로그인 방식으로 재인증해 주세요.");
        }

        switch (requestedProvider) {
            case EMAIL -> verifyPassword(identity, password);
            case KAKAO -> verifyKakao(identity, kakaoAccessToken);
        }
        return reauthTokenManager.issue(userId, clock.instant());
    }

    private void verifyPassword(LoginIdentity identity, String password) {
        if (!passwordPolicy.canVerify(password)
                || !passwordHasher.matches(password, identity.getPasswordHash())) {
            throw reauthFailed();
        }
    }

    private void verifyKakao(LoginIdentity identity, String kakaoAccessToken) {
        KakaoUserProfile profile;
        try {
            profile = kakaoUserInfoProvider.retrieve(kakaoAccessToken);
        } catch (KakaoUserInfoException exception) {
            throw reauthFailed();
        }
        if (!identity.getProviderSubject().equals(profile.subject())) {
            throw reauthFailed();
        }
    }

    private ApiException reauthFailed() {
        return new ApiException(
                ErrorCode.REAUTH_FAILED,
                "재인증 정보를 확인해 주세요.");
    }

    private ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
    }
}
