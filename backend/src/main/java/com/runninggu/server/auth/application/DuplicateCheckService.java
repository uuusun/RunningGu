package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.infrastructure.AppUserRepository;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import org.springframework.stereotype.Service;

@Service
public class DuplicateCheckService {

    private final EmailNormalizer emailNormalizer;
    private final NicknamePolicy nicknamePolicy;
    private final DuplicateCheckRateLimiter rateLimiter;
    private final LoginIdentityRepository loginIdentityRepository;
    private final AppUserRepository appUserRepository;

    public DuplicateCheckService(
            EmailNormalizer emailNormalizer,
            NicknamePolicy nicknamePolicy,
            DuplicateCheckRateLimiter rateLimiter,
            LoginIdentityRepository loginIdentityRepository,
            AppUserRepository appUserRepository) {
        this.emailNormalizer = emailNormalizer;
        this.nicknamePolicy = nicknamePolicy;
        this.rateLimiter = rateLimiter;
        this.loginIdentityRepository = loginIdentityRepository;
        this.appUserRepository = appUserRepository;
    }

    public boolean emailExists(String clientIp, String email) {
        String normalizedEmail = emailNormalizer.normalize(email);
        rateLimiter.check(clientIp, "email", normalizedEmail);
        return loginIdentityRepository.existsByProviderAndProviderSubject(
                LoginProvider.EMAIL,
                normalizedEmail);
    }

    public boolean nicknameExists(String clientIp, String nickname) {
        String nicknameKey = nicknamePolicy.duplicateKey(nickname);
        rateLimiter.check(clientIp, "nickname", nicknameKey);
        return appUserRepository.existsByNicknameKey(nicknameKey);
    }
}
