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

class CaffeineLoginAttemptRateLimiterTest {

    private final MutableClock clock =
            new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
    private final CaffeineLoginAttemptRateLimiter limiter =
            new CaffeineLoginAttemptRateLimiter(clock);

    @Test
    void 동일_IP는_성공여부와_무관하게_분당_30회까지_허용한다() {
        for (int count = 0; count < 30; count++) {
            limiter.checkIp("192.0.2.10");
        }

        assertRateLimited(() -> limiter.checkIp("192.0.2.10"));
    }

    @Test
    void 동일_이메일은_분당_5회까지_허용한다() {
        for (int count = 0; count < 5; count++) {
            limiter.checkEmail("runner@example.com");
        }

        assertRateLimited(() -> limiter.checkEmail("runner@example.com"));
    }

    @Test
    void 로그인_성공은_이메일_창만_초기화한다() {
        for (int count = 0; count < 4; count++) {
            limiter.checkEmail("success@example.com");
            limiter.checkIp("198.51.100.10");
        }

        limiter.resetEmail("success@example.com");

        for (int count = 0; count < 5; count++) {
            limiter.checkEmail("success@example.com");
        }
        for (int count = 4; count < 30; count++) {
            limiter.checkIp("198.51.100.10");
        }
        assertRateLimited(() -> limiter.checkEmail("success@example.com"));
        assertRateLimited(() -> limiter.checkIp("198.51.100.10"));
    }

    @Test
    void 정확히_60초가_지나면_IP와_이메일이_새_고정창을_연다() {
        for (int count = 0; count < 5; count++) {
            limiter.checkEmail("window@example.com");
        }
        for (int count = 0; count < 30; count++) {
            limiter.checkIp("203.0.113.10");
        }
        assertRateLimited(() -> limiter.checkEmail("window@example.com"));
        assertRateLimited(() -> limiter.checkIp("203.0.113.10"));

        clock.advanceSeconds(60);

        assertThatCode(() -> limiter.checkEmail("window@example.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkIp("203.0.113.10"))
                .doesNotThrowAnyException();
    }

    private void assertRateLimited(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(exception.getMessage())
                            .isEqualTo("로그인 시도가 많아요. 잠시 후 다시 시도해 주세요.");
                });
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
