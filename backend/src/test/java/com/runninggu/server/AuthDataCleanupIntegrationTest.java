package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.auth.application.EmailVerificationCleanupTransaction;
import com.runninggu.server.auth.application.RefreshTokenCleanupTransaction;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AuthDataCleanupIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailVerificationCleanupTransaction emailCleanup;

    @Autowired
    private RefreshTokenCleanupTransaction refreshCleanup;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE refresh_token, email_verification, login_identity, app_user "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    void 인증기록은_종류별_기준으로_정리하고_재실행해도_같다() {
        Instant cutoff = Instant.now();
        insertSignup("unverified-expired@example.com", cutoff.minusSeconds(1), null, null);
        insertSignup("unverified-active@example.com", cutoff.plusSeconds(60), null, null);
        insertSignup(
                "verified-expired@example.com",
                cutoff.plusSeconds(60),
                cutoff.minus(Duration.ofMinutes(31)),
                null);
        insertSignup(
                "verified-active@example.com",
                cutoff.minusSeconds(1),
                cutoff.minus(Duration.ofMinutes(29)),
                null);
        insertPasswordReset("reset-expired@example.com", cutoff.minusSeconds(1), null);
        insertPasswordReset("reset-active@example.com", cutoff.plusSeconds(60), null);
        insertPasswordReset("legacy-consumed@example.com", cutoff.plusSeconds(60), cutoff);

        assertThat(emailCleanup.cleanup(cutoff)).isEqualTo(4);
        assertThat(emails()).containsExactlyInAnyOrder(
                "unverified-active@example.com",
                "verified-active@example.com",
                "reset-active@example.com");
        assertThat(emailCleanup.cleanup(cutoff)).isZero();
    }

    @Test
    void 리프레시는_활성여부와_무관하게_원래_만료시각_이후에만_정리한다() {
        Instant cutoff = Instant.now();
        long userId = insertUser();
        insertRefresh(userId, cutoff.minusSeconds(1), null);
        insertRefresh(userId, cutoff.minusSeconds(1), cutoff.minusSeconds(10));
        insertRefresh(userId, cutoff.plusSeconds(60), null);
        insertRefresh(userId, cutoff.plusSeconds(60), cutoff.minusSeconds(10));

        assertThat(refreshCleanup.cleanup(cutoff)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token",
                Integer.class)).isEqualTo(2);
        assertThat(refreshCleanup.cleanup(cutoff)).isZero();
    }

    @Test
    void 정리쿼리용_인덱스가_설치된다() {
        assertThat(jdbcTemplate.queryForList(
                        """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN (
                            'ix_email_verification_expires_at',
                            'ix_email_verification_verified_at',
                            'ix_refresh_token_expires_at')
                        ORDER BY indexname
                        """,
                        String.class))
                .containsExactly(
                        "ix_email_verification_expires_at",
                        "ix_email_verification_verified_at",
                        "ix_refresh_token_expires_at");
    }

    private void insertSignup(
            String email,
            Instant expiresAt,
            Instant verifiedAt,
            Instant consumedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, code_hash, attempts, sent_at, expires_at,
                    verified_at, consumed_at)
                VALUES (?, 'SIGNUP', 'hash', 0, ?, ?, ?, ?)
                """,
                email,
                Timestamp.from(expiresAt.minusSeconds(60)),
                Timestamp.from(expiresAt),
                timestamp(verifiedAt),
                timestamp(consumedAt));
    }

    private void insertPasswordReset(String email, Instant expiresAt, Instant consumedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, token_hash, attempts, sent_at, expires_at, consumed_at)
                VALUES (?, 'PASSWORD_RESET', ?, 0, ?, ?, ?)
                """,
                email,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                Timestamp.from(expiresAt.minus(Duration.ofMinutes(30))),
                Timestamp.from(expiresAt),
                timestamp(consumedAt));
    }

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES ('정리러너', '정리러너', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class);
    }

    private void insertRefresh(long userId, Instant expiresAt, Instant revokedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                Timestamp.from(expiresAt),
                timestamp(revokedAt),
                Timestamp.from(expiresAt.minus(Duration.ofDays(14))));
    }

    private java.util.List<String> emails() {
        return jdbcTemplate.queryForList(
                "SELECT email FROM email_verification ORDER BY email",
                String.class);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
