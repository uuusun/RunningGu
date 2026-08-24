package com.runninggu.server.auth.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.runninggu.server.auth.application.PasswordResetCooldown;
import com.runninggu.server.auth.domain.EmailVerification;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 미가입 이메일도 같은 60초 제한을 적용하되 원문 이메일은 메모리에 남기지 않는다. (SPEC §4.3) */
@Component
public class CaffeinePasswordResetCooldown implements PasswordResetCooldown {

    private final Cache<String, Instant> sentAtByEmailHash = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();
    private final Clock clock;

    public CaffeinePasswordResetCooldown(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void acquire(String normalizedEmail) {
        Instant now = clock.instant();
        AtomicBoolean acquired = new AtomicBoolean();
        sentAtByEmailHash.asMap().compute(sha256(normalizedEmail), (key, previous) -> {
            if (previous == null
                    || !now.isBefore(previous.plus(EmailVerification.SEND_COOLDOWN))) {
                acquired.set(true);
                return now;
            }
            return previous;
        });
        if (!acquired.get()) {
            throw new ApiException(
                    ErrorCode.SEND_COOLDOWN,
                    "재설정 메일은 60초 후 다시 보낼 수 있습니다.");
        }
    }

    @Override
    public void release(String normalizedEmail) {
        sentAtByEmailHash.invalidate(sha256(normalizedEmail));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
