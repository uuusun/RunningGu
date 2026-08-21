package com.runninggu.server.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EmailVerificationTest {

    private static final Instant SENT_AT = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void 코드는_10분_경계에서_만료된다() {
        EmailVerification verification =
                EmailVerification.signup("runner@example.com", "hash", SENT_AT);

        assertThat(verification.isCodeExpired(SENT_AT.plusSeconds(599))).isFalse();
        assertThat(verification.isCodeExpired(SENT_AT.plusSeconds(600))).isTrue();
    }

    @Test
    void 재발송은_인증과_실패횟수를_초기화하고_60초_쿨다운을_다시_시작한다() {
        EmailVerification verification =
                EmailVerification.signup("runner@example.com", "old-hash", SENT_AT);
        verification.registerFailure();
        verification.markVerified(SENT_AT.plusSeconds(10));

        Instant resentAt = SENT_AT.plusSeconds(60);
        verification.reissue("new-hash", resentAt);

        assertThat(verification.getCodeHash()).isEqualTo("new-hash");
        assertThat(verification.getAttempts()).isZero();
        assertThat(verification.getVerifiedAt()).isNull();
        assertThat(verification.isInSendCooldown(resentAt.plusSeconds(59))).isTrue();
        assertThat(verification.isInSendCooldown(resentAt.plusSeconds(60))).isFalse();
    }

    @Test
    void 실패횟수는_5에서_고정된다() {
        EmailVerification verification =
                EmailVerification.signup("runner@example.com", "hash", SENT_AT);

        for (int count = 0; count < 8; count++) {
            verification.registerFailure();
        }

        assertThat(verification.getAttempts()).isEqualTo(5);
        assertThat(verification.isLocked()).isTrue();
    }

    @Test
    void 인증은_30분_경계에서_만료된다() {
        EmailVerification verification =
                EmailVerification.signup("runner@example.com", "hash", SENT_AT);
        verification.markVerified(SENT_AT);

        assertThat(verification.isVerifiedAndActive(SENT_AT.plusSeconds(1799))).isTrue();
        assertThat(verification.isVerifiedAndActive(SENT_AT.plusSeconds(1800))).isFalse();
    }
}
