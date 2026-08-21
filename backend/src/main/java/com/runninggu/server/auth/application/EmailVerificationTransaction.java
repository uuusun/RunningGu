package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.auth.domain.EmailVerificationPurpose;
import com.runninggu.server.auth.infrastructure.EmailVerificationLock;
import com.runninggu.server.auth.infrastructure.EmailVerificationRepository;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationTransaction {

    private static final EmailVerificationPurpose PURPOSE = EmailVerificationPurpose.SIGNUP;

    private final EmailVerificationRepository repository;
    private final EmailVerificationLock lock;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher codeHasher;
    private final VerificationMailSender mailSender;

    public EmailVerificationTransaction(
            EmailVerificationRepository repository,
            EmailVerificationLock lock,
            VerificationCodeGenerator codeGenerator,
            VerificationCodeHasher codeHasher,
            VerificationMailSender mailSender) {
        this.repository = repository;
        this.lock = lock;
        this.codeGenerator = codeGenerator;
        this.codeHasher = codeHasher;
        this.mailSender = mailSender;
    }

    @Transactional
    public void issue(String email, Instant now) {
        lock.lock(email, PURPOSE);
        EmailVerification current = repository.findByEmailAndPurpose(email, PURPOSE).orElse(null);
        if (current != null && current.isInSendCooldown(now)) {
            throw new ApiException(
                    ErrorCode.SEND_COOLDOWN,
                    "인증 메일은 60초 후 다시 보낼 수 있습니다.");
        }

        String code = codeGenerator.generate();
        String codeHash = codeHasher.hash(code);
        mailSender.sendSignupCode(email, code);

        EmailVerification next;
        if (current == null) {
            next = EmailVerification.signup(email, codeHash, now);
        } else {
            current.reissue(codeHash, now);
            next = current;
        }
        repository.saveAndFlush(next);
    }

    /** 실패 결과도 상태를 commit한 뒤 HTTP 오류로 바꾸도록 enum을 반환한다. */
    @Transactional
    public VerificationAttemptOutcome verify(String email, String code, Instant now) {
        lock.lock(email, PURPOSE);
        EmailVerification verification = repository.findByEmailAndPurpose(email, PURPOSE)
                .orElse(null);
        if (verification == null) {
            return VerificationAttemptOutcome.CODE_EXPIRED;
        }

        if (verification.getVerifiedAt() != null) {
            if (!verification.isVerifiedAndActive(now)) {
                return VerificationAttemptOutcome.CODE_EXPIRED;
            }
            return codeHasher.matches(code, verification.getCodeHash())
                    ? VerificationAttemptOutcome.VERIFIED
                    : VerificationAttemptOutcome.INVALID_CODE;
        }

        if (verification.isLocked()) {
            return VerificationAttemptOutcome.TOO_MANY_ATTEMPTS;
        }
        if (verification.isCodeExpired(now)) {
            return VerificationAttemptOutcome.CODE_EXPIRED;
        }
        if (codeHasher.matches(code, verification.getCodeHash())) {
            verification.markVerified(now);
            return VerificationAttemptOutcome.VERIFIED;
        }

        int attempts = verification.registerFailure();
        return attempts >= EmailVerification.MAX_ATTEMPTS
                ? VerificationAttemptOutcome.TOO_MANY_ATTEMPTS
                : VerificationAttemptOutcome.INVALID_CODE;
    }
}
