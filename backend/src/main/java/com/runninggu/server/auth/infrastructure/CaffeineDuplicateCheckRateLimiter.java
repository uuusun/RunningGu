package com.runninggu.server.auth.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.runninggu.server.auth.application.DuplicateCheckRateLimiter;
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

@Component
public class CaffeineDuplicateCheckRateLimiter implements DuplicateCheckRateLimiter {

    static final int IP_LIMIT = 30;
    static final int TARGET_LIMIT = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Cache<String, FixedWindow> ipWindows = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();
    private final Cache<String, FixedWindow> targetWindows = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();
    private final Clock clock;

    public CaffeineDuplicateCheckRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void check(String clientIp, String targetType, String normalizedTarget) {
        Instant now = clock.instant();
        String safeIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        if (!acquire(ipWindows, safeIp, IP_LIMIT, now)) {
            throw rateLimited();
        }

        String targetKey = sha256(targetType + '\0' + normalizedTarget);
        if (!acquire(targetWindows, targetKey, TARGET_LIMIT, now)) {
            throw rateLimited();
        }
    }

    private boolean acquire(
            Cache<String, FixedWindow> cache,
            String key,
            int limit,
            Instant now) {
        FixedWindow window = cache.get(key, ignored -> new FixedWindow(now));
        return window.tryAcquire(now, limit);
    }

    private ApiException rateLimited() {
        return new ApiException(
                ErrorCode.RATE_LIMITED,
                "중복 확인 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
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
