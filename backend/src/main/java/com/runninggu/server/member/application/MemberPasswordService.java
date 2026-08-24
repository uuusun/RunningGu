package com.runninggu.server.member.application;

import com.runninggu.server.auth.application.IssuedTokenPair;
import com.runninggu.server.auth.application.PasswordHasher;
import com.runninggu.server.auth.application.PasswordPolicy;
import com.runninggu.server.auth.application.RefreshTokenHasher;
import com.runninggu.server.auth.application.TokenIssuer;
import com.runninggu.server.auth.application.TokenPair;
import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.domain.RefreshToken;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EMAIL 비밀번호와 사용자 Refresh 세션을 한 트랜잭션에서 교체한다. (SPEC 결정-38, NFR-11) */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemberPasswordService {

    private final LoginIdentityRepository loginIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Clock clock;

    public MemberPasswordService(
            LoginIdentityRepository loginIdentityRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            TokenIssuer tokenIssuer,
            RefreshTokenHasher refreshTokenHasher,
            Clock clock) {
        this.loginIdentityRepository = loginIdentityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokenHasher = refreshTokenHasher;
        this.clock = clock;
    }

    @Transactional
    public TokenPair changePassword(long userId, String currentPassword, String newPassword) {
        LoginIdentity identity = loginIdentityRepository.findByUserIdForUpdate(userId)
                .orElseThrow(this::unauthorized);
        if (identity.getProvider() != LoginProvider.EMAIL) {
            throw new ApiException(
                    ErrorCode.EMAIL_IDENTITY_REQUIRED,
                    "이메일 로그인 계정에서만 비밀번호를 변경할 수 있습니다.");
        }
        if (!passwordPolicy.canVerify(currentPassword)
                || !passwordHasher.matches(currentPassword, identity.getPasswordHash())) {
            throw new ApiException(
                    ErrorCode.CURRENT_PASSWORD_MISMATCH,
                    "현재 비밀번호가 올바르지 않습니다.");
        }

        passwordPolicy.validate(newPassword);
        identity.changeEmailPassword(passwordHasher.hash(newPassword));

        Instant now = clock.instant();
        refreshTokenRepository.findAllByUser_IdAndRevokedAtIsNull(userId)
                .forEach(token -> token.revoke(now));
        refreshTokenRepository.flush();

        IssuedTokenPair issued = tokenIssuer.issue(userId, UUID.randomUUID(), now);
        refreshTokenRepository.saveAndFlush(RefreshToken.issue(
                identity.getUser(),
                issued.familyId(),
                refreshTokenHasher.hash(issued.refreshToken()),
                issued.refreshExpiresAt(),
                now));
        return new TokenPair(issued.accessToken(), issued.refreshToken());
    }

    private ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
    }
}
