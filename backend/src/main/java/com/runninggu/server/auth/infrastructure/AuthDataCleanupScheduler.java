package com.runninggu.server.auth.infrastructure;

import com.runninggu.server.auth.application.EmailVerificationCleanupTransaction;
import com.runninggu.server.auth.application.RefreshTokenCleanupTransaction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthDataCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthDataCleanupScheduler.class);
    private static final Duration URGENT_AFTER = Duration.ofHours(23);

    private final EmailVerificationCleanupTransaction emailCleanup;
    private final RefreshTokenCleanupTransaction refreshCleanup;
    private final Clock clock;
    private final CleanupState emailState = new CleanupState();
    private final CleanupState refreshState = new CleanupState();

    public AuthDataCleanupScheduler(
            EmailVerificationCleanupTransaction emailCleanup,
            RefreshTokenCleanupTransaction refreshCleanup,
            Clock clock) {
        this.emailCleanup = emailCleanup;
        this.refreshCleanup = refreshCleanup;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        runAll();
    }

    /** 각 테이블은 독립 트랜잭션이라 한쪽 실패가 다른 정리를 막지 않는다. (SPEC §6.5, 결정-57) */
    @Scheduled(
            scheduler = "authDataCleanupTaskScheduler",
            fixedDelayString = "${runninggu.auth.cleanup.interval:1h}",
            initialDelayString = "${runninggu.auth.cleanup.interval:1h}")
    public void cleanupHourly() {
        runAll();
    }

    void runAll() {
        Instant cutoff = clock.instant();
        runOne(
                "email_verification",
                cutoff,
                emailState,
                () -> emailCleanup.cleanup(cutoff));
        runOne(
                "refresh_token",
                cutoff,
                refreshState,
                () -> refreshCleanup.cleanup(cutoff));
    }

    private void runOne(
            String table,
            Instant cutoff,
            CleanupState state,
            CleanupOperation operation) {
        try {
            int deleted = operation.cleanup();
            state.recordSuccess(cutoff);
            log.info("인증 데이터 정리 완료 table={} deleted={} cutoff={}", table, deleted, cutoff);
        } catch (RuntimeException exception) {
            int consecutiveFailures = state.recordFailure();
            Duration sinceLastSuccess = state.sinceLastSuccess(cutoff);
            if (consecutiveFailures >= 2
                    || (sinceLastSuccess != null && sinceLastSuccess.compareTo(URGENT_AFTER) >= 0)) {
                log.error(
                        "인증 데이터 정리 긴급 경고 table={} consecutiveFailures={} lastSuccessAt={}",
                        table,
                        consecutiveFailures,
                        state.lastSuccessAt(),
                        exception);
            } else {
                log.warn(
                        "인증 데이터 정리 실패 table={} consecutiveFailures={} lastSuccessAt={}",
                        table,
                        consecutiveFailures,
                        state.lastSuccessAt(),
                        exception);
            }
        }
    }

    @FunctionalInterface
    private interface CleanupOperation {
        int cleanup();
    }

    private static final class CleanupState {
        private Instant lastSuccessAt;
        private int consecutiveFailures;

        synchronized void recordSuccess(Instant completedAt) {
            lastSuccessAt = completedAt;
            consecutiveFailures = 0;
        }

        synchronized int recordFailure() {
            return ++consecutiveFailures;
        }

        synchronized Duration sinceLastSuccess(Instant now) {
            return lastSuccessAt == null ? null : Duration.between(lastSuccessAt, now);
        }

        synchronized Instant lastSuccessAt() {
            return lastSuccessAt;
        }
    }
}
