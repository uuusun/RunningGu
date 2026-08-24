package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.auth.domain.EmailVerificationPurpose;
import com.runninggu.server.auth.domain.LoginIdentity;
import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.infrastructure.EmailVerificationLock;
import com.runninggu.server.auth.infrastructure.EmailVerificationRepository;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.RefreshTokenRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetTransaction {

    private static final EmailVerificationPurpose PURPOSE =
            EmailVerificationPurpose.PASSWORD_RESET;

    private final EmailVerificationRepository verificationRepository;
    private final EmailVerificationLock verificationLock;
    private final LoginIdentityRepository loginIdentityRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenManager tokenManager;
    private final PasswordHasher passwordHasher;
    private final VerificationMailSender mailSender;

    public PasswordResetTransaction(
            EmailVerificationRepository verificationRepository,
            EmailVerificationLock verificationLock,
            LoginIdentityRepository loginIdentityRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenManager tokenManager,
            PasswordHasher passwordHasher,
            VerificationMailSender mailSender) {
        this.verificationRepository = verificationRepository;
        this.verificationLock = verificationLock;
        this.loginIdentityRepository = loginIdentityRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenManager = tokenManager;
        this.passwordHasher = passwordHasher;
        this.mailSender = mailSender;
    }

    @Transactional
    public void issueIfEmailIdentityExists(String email, Instant now) {
        verificationLock.lock(email, PURPOSE);
        LoginIdentity identity = loginIdentityRepository
                .findByProviderAndProviderSubject(LoginProvider.EMAIL, email)
                .orElse(null);
        if (identity == null) {
            return;
        }

        EmailVerification current = verificationRepository
                .findByEmailAndPurpose(email, PURPOSE)
                .orElse(null);
        if (current != null && current.isInSendCooldown(now)) {
            throw new ApiException(
                    ErrorCode.SEND_COOLDOWN,
                    "재설정 메일은 60초 후 다시 보낼 수 있습니다.");
        }

        String rawToken = tokenManager.generate();
        String tokenHash = tokenManager.hash(rawToken);
        mailSender.sendPasswordResetLink(email, rawToken);

        if (current == null) {
            verificationRepository.saveAndFlush(
                    EmailVerification.passwordReset(email, tokenHash, now));
        } else {
            current.reissuePasswordReset(tokenHash, now);
            verificationRepository.flush();
        }
    }

    /** 토큰 소비·비밀번호 교체·전체 세션 폐기를 한 트랜잭션으로 묶는다. (SPEC §4.3, NFR-11) */
    @Transactional
    public boolean reset(String tokenHash, String newPassword, Instant now) {
        List<EmailVerification> matches = verificationRepository.findAllByTokenHash(tokenHash);
        if (matches.size() != 1) {
            return false;
        }

        EmailVerification verification = matches.getFirst();
        if (!verification.isPasswordResetActive(now)) {
            return false;
        }

        LoginIdentity identity = loginIdentityRepository
                .findByProviderAndProviderSubjectForUpdate(
                        LoginProvider.EMAIL,
                        verification.getEmail())
                .orElse(null);
        if (identity == null) {
            return false;
        }

        identity.changeEmailPassword(passwordHasher.hash(newPassword));
        verification.markConsumed(now);
        refreshTokenRepository.findAllByUser_IdAndRevokedAtIsNull(identity.getUser().getId())
                .forEach(refreshToken -> refreshToken.revoke(now));
        refreshTokenRepository.flush();
        return true;
    }
}
