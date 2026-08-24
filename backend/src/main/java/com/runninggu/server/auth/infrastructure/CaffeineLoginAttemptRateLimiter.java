package com.runninggu.server.auth.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.runninggu.server.auth.application.LoginAttemptRateLimiter;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/** 단일 서버 P0의 고정 1분 로그인 제한이다. (SPEC §4.1, 결정-55) */
@Component
public class CaffeineLoginAttemptRateLimiter implements LoginAttemptRateLimiter {

    static final int IP_LIMIT = 30;
    static final int EMAIL_LIMIT = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String RATE_LIMIT_DETAIL =
            "로그인 시도가 많아요. 잠시 후 다시 시도해 주세요.";

    private final Cache<String, FixedWindow> ipWindows = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();
    private final Cache<String, FixedWindow> emailWindows = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();
    private final Clock clock;

    public CaffeineLoginAttemptRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void checkIp(String clientIp) {
        String safeIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        check(ipWindows, safeIp, IP_LIMIT);
    }

    @Override
    public void checkEmail(String normalizedEmail) {
        check(emailWindows, sha256(normalizedEmail), EMAIL_LIMIT);
    }

    @Override
    public void resetEmail(String normalizedEmail) {
        emailWindows.invalidate(sha256(normalizedEmail));
    }

    private void check(Cache<String, FixedWindow> cache, String key, int limit) {
        Instant now = clock.instant();
        FixedWindow window = cache.get(key, ignored -> new FixedWindow(now));
        if (!window.tryAcquire(now, limit)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, RATE_LIMIT_DETAIL);
        }
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

    private static final class FixedWindow {
        private Instant startedAt;
        private int count;

        private FixedWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private synchronized boolean tryAcquire(Instant now, int limit) {
            if (!now.isBefore(startedAt.plus(WINDOW))) {
                startedAt = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }
}
