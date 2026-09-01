package com.runninggu.server.member.application;

import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.auth.infrastructure.EmailVerificationRepository;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemberDeletionService {

    private final ReauthTokenManager reauthTokenManager;
    private final LoginIdentityRepository loginIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final AppUserRepository appUserRepository;

    public MemberDeletionService(
            ReauthTokenManager reauthTokenManager,
            LoginIdentityRepository loginIdentityRepository,
            RefreshTokenRepository refreshTokenRepository,
            EmailVerificationRepository emailVerificationRepository,
            AppUserRepository appUserRepository) {
        this.reauthTokenManager = reauthTokenManager;
        this.loginIdentityRepository = loginIdentityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public void delete(long userId, String rawReauthToken) {
        long reauthenticatedUserId = decode(rawReauthToken);
        if (reauthenticatedUserId != userId) {
            throw invalidToken();
        }

        LoginIdentity identity = loginIdentityRepository.findByUserIdForUpdate(userId)
                .orElseThrow(this::unauthorized);
        // LOGIN_IDENTITY → EMAIL_VERIFICATION → REFRESH_TOKEN 순서로 잠근다. (SPEC §6.5, 결정-57)
        if (identity.getProvider() == LoginProvider.EMAIL) {
            emailVerificationRepository.deleteAllByEmailInIdOrder(
                    identity.getProviderSubject());
        }
        refreshTokenRepository.deleteAllByUserIdInIdOrder(userId);
        appUserRepository.deleteAllByIdInBatch(List.of(userId));
    }

    private long decode(String rawReauthToken) {
        if (rawReauthToken == null || rawReauthToken.isBlank()) {
            throw invalidToken();
        }
        try {
            return reauthTokenManager.decodeUserId(rawReauthToken);
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private ApiException invalidToken() {
        return new ApiException(
                ErrorCode.INVALID_REAUTH_TOKEN,
                "유효한 탈퇴 재인증 토큰이 필요합니다.");
    }

    private ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
    }
}
