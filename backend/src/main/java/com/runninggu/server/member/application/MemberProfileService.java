package com.runninggu.server.member.application;

import com.runninggu.server.auth.application.NicknamePolicy;
import com.runninggu.server.auth.domain.AgreementType;
import com.runninggu.server.auth.domain.AppUser;
import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.domain.UserAgreement;
import com.runninggu.server.auth.infrastructure.AgreementProperties;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.UserAgreementRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 프로필과 현재 약관 상태의 서버 SSOT를 제공한다. (SPEC §4.13, API 명세 §2) */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MemberProfileService {

    private final AppUserRepository appUserRepository;
    private final LoginIdentityRepository loginIdentityRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final NicknamePolicy nicknamePolicy;
    private final AgreementProperties agreementProperties;
    private final Clock businessClock;

    public MemberProfileService(
            AppUserRepository appUserRepository,
            LoginIdentityRepository loginIdentityRepository,
            UserAgreementRepository userAgreementRepository,
            NicknamePolicy nicknamePolicy,
            AgreementProperties agreementProperties,
            Clock businessClock) {
        this.appUserRepository = appUserRepository;
        this.loginIdentityRepository = loginIdentityRepository;
        this.userAgreementRepository = userAgreementRepository;
        this.nicknamePolicy = nicknamePolicy;
        this.agreementProperties = agreementProperties;
        this.businessClock = businessClock;
    }

    @Transactional(readOnly = true)
    public MemberProfile getProfile(long userId) {
        return describe(requireIdentity(userId));
    }

    @Transactional
    public MemberProfile updateNickname(long userId, String nickname) {
        AppUser user = requireUserForUpdate(userId);
        String displayNickname = nicknamePolicy.normalizeDisplay(nickname);
        String nicknameKey = nicknamePolicy.duplicateKey(displayNickname);

        if (appUserRepository.existsByNicknameKeyAndIdNot(nicknameKey, userId)) {
            throw new ApiException(
                    ErrorCode.NICKNAME_DUPLICATED,
                    "이미 사용 중인 닉네임입니다.");
        }
        if (!user.getNickname().equals(displayNickname)) {
            user.changeNickname(displayNickname, nicknameKey, Instant.now(businessClock));
            appUserRepository.flush();
        }
        return describe(requireIdentity(userId));
    }

    /** 선택 동의는 값이 바뀔 때만 새 이력을 남긴다. (SPEC NFR-12, 결정-52) */
    @Transactional
    public MemberProfile updateMarketingAgreement(long userId, boolean marketing) {
        AppUser user = requireUserForUpdate(userId);
        Map<AgreementType, Boolean> agreements = currentAgreements(userId);
        boolean currentMarketing = requireAgreement(agreements, AgreementType.MARKETING);

        if (currentMarketing != marketing) {
            userAgreementRepository.save(UserAgreement.record(
                    user,
                    AgreementType.MARKETING,
                    agreementProperties.marketingVersion(),
                    marketing,
                    Instant.now(businessClock)));
            userAgreementRepository.flush();
        }
        return describe(requireIdentity(userId));
    }

    private MemberProfile describe(LoginIdentity identity) {
        AppUser user = identity.getUser();
        Map<AgreementType, Boolean> agreements = currentAgreements(user.getId());
        return new MemberProfile(
                user.getId(),
                emailOf(identity),
                user.getNickname(),
                identity.getProvider(),
                new MemberProfile.Agreements(
                        requireAgreement(agreements, AgreementType.TOS),
                        requireAgreement(agreements, AgreementType.PRIVACY),
                        requireAgreement(agreements, AgreementType.MARKETING)),
                user.getCreatedAt());
    }

    private Map<AgreementType, Boolean> currentAgreements(long userId) {
        List<UserAgreement> history =
                userAgreementRepository.findByUser_IdOrderByChangedAtDescIdDesc(userId);
        Map<AgreementType, Boolean> current = new EnumMap<>(AgreementType.class);
        for (UserAgreement agreement : history) {
            current.putIfAbsent(agreement.getType(), agreement.isAgreed());
        }
        return current;
    }

    private boolean requireAgreement(
            Map<AgreementType, Boolean> agreements,
            AgreementType type) {
        Boolean agreed = agreements.get(type);
        if (agreed == null) {
            throw new ApiException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "회원 약관 상태를 확인할 수 없습니다.");
        }
        return agreed;
    }

    private String emailOf(LoginIdentity identity) {
        return identity.getProvider() == LoginProvider.EMAIL
                ? identity.getProviderSubject()
                : identity.getEmailSnapshot();
    }

    private AppUser requireUserForUpdate(long userId) {
        return appUserRepository.findByIdForUpdate(userId)
                .orElseThrow(this::unauthorized);
    }

    private LoginIdentity requireIdentity(long userId) {
        return loginIdentityRepository.findByUser_Id(userId)
                .orElseThrow(this::unauthorized);
    }

    private ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "사용자 세션을 확인할 수 없습니다.");
    }
}
