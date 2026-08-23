package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.AgreementType;
import com.runninggu.server.auth.domain.AppUser;
import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.domain.RefreshToken;
import com.runninggu.server.auth.domain.UserAgreement;
import com.runninggu.server.auth.infrastructure.AgreementProperties;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.auth.infrastructure.KakaoSignupLock;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import com.runninggu.server.auth.infrastructure.UserAgreementRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KakaoAuthTransaction {

    private final NicknamePolicy nicknamePolicy;
    private final AppUserRepository appUserRepository;
    private final LoginIdentityRepository loginIdentityRepository;
    private final UserAgreementRepository agreementRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final TokenIssuer tokenIssuer;
    private final AgreementProperties agreementProperties;
    private final KakaoSignupLock signupLock;
    private final Clock clock;

    public KakaoAuthTransaction(
            NicknamePolicy nicknamePolicy,
            AppUserRepository appUserRepository,
            LoginIdentityRepository loginIdentityRepository,
            UserAgreementRepository agreementRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher,
            TokenIssuer tokenIssuer,
            AgreementProperties agreementProperties,
            KakaoSignupLock signupLock,
            Clock clock) {
        this.nicknamePolicy = nicknamePolicy;
        this.appUserRepository = appUserRepository;
        this.loginIdentityRepository = loginIdentityRepository;
        this.agreementRepository = agreementRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.tokenIssuer = tokenIssuer;
        this.agreementProperties = agreementProperties;
        this.signupLock = signupLock;
        this.clock = clock;
    }

    @Transactional
    public KakaoLoginResult login(KakaoUserProfile profile) {
        LoginIdentity identity = loginIdentityRepository
                .findByProviderAndProviderSubject(LoginProvider.KAKAO, profile.subject())
                .orElse(null);
        if (identity == null) {
            return KakaoLoginResult.signupRequired(profile);
        }

        Instant now = clock.instant();
        identity.markLoggedIn(now);
        IssuedTokenPair issued = issueNewFamily(identity.getUser(), now);
        return KakaoLoginResult.existing(result(issued, identity));
    }

    /** USER·KAKAO identity·약관·첫 세션을 한 트랜잭션에서 생성한다. (SPEC §4.2, §6.5) */
    @Transactional
    public AuthSessionResult signup(
            KakaoUserProfile profile,
            String nickname,
            boolean tos,
            boolean privacy,
            boolean marketing) {
        signupLock.lock(profile.subject());
        if (loginIdentityRepository.existsByProviderAndProviderSubject(
                LoginProvider.KAKAO,
                profile.subject())) {
            throw kakaoDuplicated();
        }

        String displayNickname = nicknamePolicy.normalizeDisplay(nickname);
        String nicknameKey = nicknamePolicy.duplicateKey(displayNickname);
        if (!tos || !privacy) {
            throw new ApiException(
                    ErrorCode.AGREEMENT_REQUIRED,
                    "이용약관과 개인정보 수집·이용 동의가 필요합니다.");
        }
        if (appUserRepository.existsByNicknameKey(nicknameKey)) {
            throw new ApiException(ErrorCode.NICKNAME_DUPLICATED, "이미 사용 중인 닉네임입니다.");
        }

        Instant now = clock.instant();
        AppUser user = appUserRepository.saveAndFlush(
                AppUser.create(displayNickname, nicknameKey, now));
        LoginIdentity identity = loginIdentityRepository.saveAndFlush(LoginIdentity.kakao(
                user,
                profile.subject(),
                profile.email(),
                now));
        agreementRepository.saveAll(List.of(
                UserAgreement.record(
                        user,
                        AgreementType.TOS,
                        agreementProperties.tosVersion(),
                        true,
                        now),
                UserAgreement.record(
                        user,
                        AgreementType.PRIVACY,
                        agreementProperties.privacyVersion(),
                        true,
                        now),
                UserAgreement.record(
                        user,
                        AgreementType.MARKETING,
                        agreementProperties.marketingVersion(),
                        marketing,
                        now)));

        IssuedTokenPair issued = issueNewFamily(user, now);
        return result(issued, identity);
    }

    private IssuedTokenPair issueNewFamily(AppUser user, Instant now) {
        IssuedTokenPair issued = tokenIssuer.issue(user.getId(), UUID.randomUUID(), now);
        refreshTokenRepository.save(RefreshToken.issue(
                user,
                issued.familyId(),
                refreshTokenHasher.hash(issued.refreshToken()),
                issued.refreshExpiresAt(),
                now));
        return issued;
    }

    private AuthSessionResult result(IssuedTokenPair issued, LoginIdentity identity) {
        AppUser user = identity.getUser();
        return new AuthSessionResult(
                issued.accessToken(),
                issued.refreshToken(),
                new AuthenticatedUser(
                        user.getId(),
                        identity.getEmailSnapshot(),
                        user.getNickname(),
                        LoginProvider.KAKAO));
    }

    private ApiException kakaoDuplicated() {
        return new ApiException(
                ErrorCode.KAKAO_ACCOUNT_DUPLICATED,
                "이미 가입된 카카오 계정입니다.");
    }
}
