package com.runninggu.server.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.runninggu.server.auth.application.EmailVerificationCleanupTransaction;
import com.runninggu.server.auth.application.RefreshTokenCleanupTransaction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthDataCleanupSchedulerTest {

    @Test
    void 한_테이블이_실패해도_개인정보를_로그에_남기지_않고_다음_주기에_재시도한다(
            CapturedOutput output) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        String privateEmail = "cleanup-log-user@example.test";
        String privateToken = "cleanup-log-private-token";
        EmailVerificationCleanupTransaction emailCleanup =
                mock(EmailVerificationCleanupTransaction.class);
        RefreshTokenCleanupTransaction refreshCleanup =
                mock(RefreshTokenCleanupTransaction.class);
        when(emailCleanup.cleanup(now))
                .thenThrow(new IllegalStateException(
                        "email=" + privateEmail,
                        new IllegalArgumentException("token=" + privateToken)))
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
        assertThat(output.getAll())
                .doesNotContain(privateEmail)
                .doesNotContain(privateToken)
                .contains("table=email_verification")
                .contains("exceptionType=java.lang.IllegalStateException");
    }
}
