package com.runninggu.server.auth.application;

import com.runninggu.server.auth.infrastructure.MailDeliveryException;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final EmailNormalizer emailNormalizer;
    private final PasswordResetCooldown cooldown;
    private final PasswordResetTransaction transaction;
    private final PasswordResetTokenManager tokenManager;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;

    public PasswordResetService(
            EmailNormalizer emailNormalizer,
            PasswordResetCooldown cooldown,
            PasswordResetTransaction transaction,
            PasswordResetTokenManager tokenManager,
            PasswordPolicy passwordPolicy,
            Clock clock) {
        this.emailNormalizer = emailNormalizer;
        this.cooldown = cooldown;
        this.transaction = transaction;
        this.tokenManager = tokenManager;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
    }

    public void request(String email) {
        String normalizedEmail = emailNormalizer.normalize(email);
        cooldown.acquire(normalizedEmail);
        try {
            transaction.issueIfEmailIdentityExists(normalizedEmail, clock.instant());
        } catch (MailDeliveryException exception) {
            cooldown.release(normalizedEmail);
            log.warn("비밀번호 재설정 메일 발송에 실패했습니다.");
        } catch (ApiException exception) {
            if (exception.errorCode() != ErrorCode.SEND_COOLDOWN) {
                cooldown.release(normalizedEmail);
            }
            throw exception;
        } catch (RuntimeException exception) {
            cooldown.release(normalizedEmail);
            throw exception;
        }
    }

    public void reset(String rawToken, String newPassword) {
        passwordPolicy.validate(newPassword);
        boolean reset = transaction.reset(
                tokenManager.hash(rawToken),
                newPassword,
                clock.instant());
        if (!reset) {
            throw new ApiException(
                    ErrorCode.INVALID_RESET_TOKEN,
                    "비밀번호 재설정 링크가 만료되었거나 이미 사용됐습니다.");
        }
    }
}
