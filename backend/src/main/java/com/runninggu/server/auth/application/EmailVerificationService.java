package com.runninggu.server.auth.application;

import com.runninggu.server.auth.domain.LoginProvider;
import com.runninggu.server.auth.infrastructure.LoginIdentityRepository;
import com.runninggu.server.auth.infrastructure.MailDeliveryException;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[0-9]{6}$");

    private final EmailNormalizer emailNormalizer;
    private final LoginIdentityRepository loginIdentityRepository;
    private final EmailVerificationTransaction transaction;
    private final Clock clock;

    public EmailVerificationService(
            EmailNormalizer emailNormalizer,
            LoginIdentityRepository loginIdentityRepository,
            EmailVerificationTransaction transaction,
            Clock clock) {
        this.emailNormalizer = emailNormalizer;
        this.loginIdentityRepository = loginIdentityRepository;
        this.transaction = transaction;
        this.clock = clock;
    }

    public void sendSignupCode(String email) {
        String normalizedEmail = emailNormalizer.normalize(email);
        if (loginIdentityRepository.existsByProviderAndProviderSubject(
                LoginProvider.EMAIL,
                normalizedEmail)) {
            throw new ApiException(
                    ErrorCode.EMAIL_DUPLICATED,
                    "이미 가입된 이메일입니다.");
        }

        try {
            transaction.issue(normalizedEmail, clock.instant());
        } catch (MailDeliveryException exception) {
            throw new ApiException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "인증 메일을 발송하지 못했습니다.");
        }
    }

    public boolean verifySignupCode(String email, String code) {
        String normalizedEmail = emailNormalizer.normalize(email);
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "code 값은 6자리 숫자여야 합니다.");
        }

        VerificationAttemptOutcome outcome =
                transaction.verify(normalizedEmail, code, clock.instant());
        return switch (outcome) {
            case VERIFIED -> true;
            case INVALID_CODE -> throw new ApiException(
                    ErrorCode.INVALID_CODE,
                    "인증 코드가 올바르지 않습니다.");
            case CODE_EXPIRED -> throw new ApiException(
                    ErrorCode.CODE_EXPIRED,
                    "인증 코드가 만료됐습니다. 다시 발급해 주세요.");
            case TOO_MANY_ATTEMPTS -> throw new ApiException(
                    ErrorCode.TOO_MANY_ATTEMPTS,
                    "인증 시도 횟수를 초과했습니다. 코드를 다시 발급해 주세요.");
        };
    }
}
