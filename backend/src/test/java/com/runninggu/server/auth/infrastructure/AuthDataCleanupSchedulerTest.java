package com.runninggu.server.auth.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.runninggu.server.auth.application.EmailVerificationCleanupTransaction;
import com.runninggu.server.auth.application.RefreshTokenCleanupTransaction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthDataCleanupSchedulerTest {

    @Test
    void 한_테이블이_실패해도_다른_정리를_실행하고_다음_주기에_재시도한다() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        EmailVerificationCleanupTransaction emailCleanup =
                mock(EmailVerificationCleanupTransaction.class);
        RefreshTokenCleanupTransaction refreshCleanup =
                mock(RefreshTokenCleanupTransaction.class);
        when(emailCleanup.cleanup(now))
                .thenThrow(new IllegalStateException("첫 실패"))
                .thenReturn(1);
        when(refreshCleanup.cleanup(now)).thenReturn(2);
        AuthDataCleanupScheduler scheduler = new AuthDataCleanupScheduler(
                emailCleanup,
                refreshCleanup,
                Clock.fixed(now, ZoneOffset.UTC));

        scheduler.runAll();
        scheduler.runAll();

        verify(emailCleanup, org.mockito.Mockito.times(2)).cleanup(now);
        verify(refreshCleanup, org.mockito.Mockito.times(2)).cleanup(now);
    }
}
