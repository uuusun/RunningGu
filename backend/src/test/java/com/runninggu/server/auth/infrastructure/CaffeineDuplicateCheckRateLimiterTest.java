package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CaffeineDuplicateCheckRateLimiterTest {

    private final MutableClock clock =
            new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
    private final CaffeineDuplicateCheckRateLimiter limiter =
            new CaffeineDuplicateCheckRateLimiter(clock);

    @Test
    void 동일_정규화_대상은_IP와_무관하게_분당_5회까지_허용한다() {
        for (int count = 0; count < 5; count++) {
            limiter.check("192.0.2." + count, "email", "runner@example.com");
        }

        assertRateLimited(() ->
                limiter.check("192.0.2.99", "email", "runner@example.com"));
    }

    @Test
    void 동일_IP는_대상_종류를_합쳐_분당_30회까지_허용한다() {
        for (int count = 0; count < 30; count++) {
            limiter.check("192.0.2.10", "email", "runner" + count + "@example.com");
        }

        assertRateLimited(() ->
                limiter.check("192.0.2.10", "nickname", "새로운닉네임"));
    }

    @Test
    void 정확히_60초가_지나면_새_고정창으로_초기화한다() {
        for (int count = 0; count < 5; count++) {
            limiter.check("198.51.100." + count, "nickname", "Runner");
        }
        assertRateLimited(() ->
                limiter.check("198.51.100.10", "nickname", "Runner"));

        clock.advanceSeconds(60);

        assertThatCode(() -> limiter.check(
                        "198.51.100.10",
                        "nickname",
                        "Runner"))
                .doesNotThrowAnyException();
    }

    private void assertRateLimited(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
