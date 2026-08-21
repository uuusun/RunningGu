package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.AgreementType;
import com.runninggu.server.auth.domain.AppUser;
import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.auth.domain.EmailVerificationPurpose;
import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.domain.RefreshToken;
import com.runninggu.server.auth.domain.UserAgreement;
import com.runninggu.server.auth.infrastructure.AgreementProperties;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.auth.infrastructure.EmailVerificationRepository;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import com.runninggu.server.auth.infrastructure.UserAgreementRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailAuthService {

    private static final EmailVerificationPurpose SIGNUP = EmailVerificationPurpose.SIGNUP;
    private static final String LOGIN_FAILURE_DETAIL = "이메일 또는 비밀번호를 확인해 주세요.";

    private final EmailNormalizer emailNormalizer;
    private final NicknamePolicy nicknamePolicy;
    private final PasswordPolicy passwordPolicy;
    private final PasswordHasher passwordHasher;
    private final AppUserRepository appUserRepository;
    private final LoginIdentityRepository loginIdentityRepository;
    private final EmailVerificationRepository verificationRepository;
    private final UserAgreementRepository agreementRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final TokenIssuer tokenIssuer;
    private final AgreementProperties agreementProperties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public EmailAuthService(
            EmailNormalizer emailNormalizer,
            NicknamePolicy nicknamePolicy,
            PasswordPolicy passwordPolicy,
            PasswordHasher passwordHasher,
            AppUserRepository appUserRepository,
            LoginIdentityRepository loginIdentityRepository,
            EmailVerificationRepository verificationRepository,
            UserAgreementRepository agreementRepository,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenHasher refreshTokenHasher,
            TokenIssuer tokenIssuer,
            AgreementProperties agreementProperties,
            Clock clock) {
        this.emailNormalizer = emailNormalizer;
        this.nicknamePolicy = nicknamePolicy;
        this.passwordPolicy = passwordPolicy;
        this.passwordHasher = passwordHasher;
        this.appUserRepository = appUserRepository;
        this.loginIdentityRepository = loginIdentityRepository;
        this.verificationRepository = verificationRepository;
        this.agreementRepository = agreementRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.tokenIssuer = tokenIssuer;
        this.agreementProperties = agreementProperties;
        this.clock = clock;
        this.dummyPasswordHash = passwordHasher.hash("RunningGuDummy1");
    }

    /** 인증 소비·계정·약관·첫 세션을 한 트랜잭션으로 생성한다. (SPEC §4.2, §6.5) */
    @Transactional
    public AuthSessionResult signup(
            String email,
            String password,
            String nickname,
            boolean tos,
            boolean privacy,
            boolean marketing) {
        String normalizedEmail = emailNormalizer.normalize(email);
        passwordPolicy.validate(password);
        String displayNickname = nicknamePolicy.normalizeDisplay(nickname);
        String nicknameKey = nicknamePolicy.duplicateKey(displayNickname);
        if (!tos || !privacy) {
            throw new ApiException(
                    ErrorCode.AGREEMENT_REQUIRED,
                    "이용약관과 개인정보 수집·이용 동의가 필요합니다.");
        }

        EmailVerification verification = verificationRepository
                .findByEmailAndPurpose(normalizedEmail, SIGNUP)
                .orElse(null);
        if (loginIdentityRepository.existsByProviderAndProviderSubject(
                LoginProvider.EMAIL,
                normalizedEmail)) {
            throw new ApiException(ErrorCode.EMAIL_DUPLICATED, "이미 가입된 이메일입니다.");
        }
        if (appUserRepository.existsByNicknameKey(nicknameKey)) {
            throw new ApiException(ErrorCode.NICKNAME_DUPLICATED, "이미 사용 중인 닉네임입니다.");
        }

        Instant now = clock.instant();
        if (verification == null || !verification.isVerifiedAndActive(now)) {
            throw new ApiException(
                    ErrorCode.EMAIL_NOT_VERIFIED,
                    "30분 안에 완료된 이메일 인증이 필요합니다.");
        }

        AppUser user = appUserRepository.saveAndFlush(
                AppUser.create(displayNickname, nicknameKey, now));
        LoginIdentity identity = LoginIdentity.email(
                user,
                normalizedEmail,
                passwordHasher.hash(password),
                verification.getVerifiedAt(),
                now);
        loginIdentityRepository.save(identity);
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
        verification.markConsumed(now);

        IssuedTokenPair issued = issueNewFamily(user, now);
        return result(issued, user, normalizedEmail);
    }

    @Transactional
    public AuthSessionResult login(String email, String password) {
        String normalizedEmail;
        try {
            normalizedEmail = emailNormalizer.normalize(email);
        } catch (ApiException exception) {
            performDummyMatch(password);
            throw loginFailed();
        }

        LoginIdentity identity = loginIdentityRepository
                .findByProviderAndProviderSubject(LoginProvider.EMAIL, normalizedEmail)
                .orElse(null);
        if (identity == null) {
            performDummyMatch(password);
            throw loginFailed();
        }
        if (!passwordPolicy.canVerify(password)
                || !passwordHasher.matches(password, identity.getPasswordHash())) {
            if (!passwordPolicy.canVerify(password)) {
                performDummyMatch(password);
            }
            throw loginFailed();
        }

        Instant now = clock.instant();
        identity.markLoggedIn(now);
        IssuedTokenPair issued = issueNewFamily(identity.getUser(), now);
        return result(issued, identity.getUser(), normalizedEmail);
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

    private AuthSessionResult result(
            IssuedTokenPair issued,
            AppUser user,
            String normalizedEmail) {
        return new AuthSessionResult(
                issued.accessToken(),
                issued.refreshToken(),
                new AuthenticatedUser(
                        user.getId(),
                        normalizedEmail,
                        user.getNickname(),
                        LoginProvider.EMAIL));
    }

    private void performDummyMatch(String password) {
        String candidate = passwordPolicy.canVerify(password) ? password : "InvalidDummy1";
        passwordHasher.matches(candidate, dummyPasswordHash);
    }

    private ApiException loginFailed() {
        return new ApiException(ErrorCode.LOGIN_FAILED, LOGIN_FAILURE_DETAIL);
    }
}
