package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.auth.application.PasswordHasher;
import com.runninggu.server.auth.application.PasswordResetTokenManager;
import com.runninggu.server.auth.application.PasswordResetTransaction;
import com.runninggu.server.auth.application.VerificationMailSender;
import com.runninggu.server.member.application.MemberDeletionService;
import com.runninggu.server.member.application.ReauthTokenManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class AuthLifecycleConcurrencyIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private PasswordResetTokenManager tokenManager;

    @Autowired
    private PasswordResetTransaction passwordResetTransaction;

    @Autowired
    private MemberDeletionService memberDeletionService;

    @Autowired
    private ReauthTokenManager reauthTokenManager;

    @MockitoBean
    private VerificationMailSender mailSender;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE refresh_token, email_verification, login_identity, app_user "
                        + "RESTART IDENTITY CASCADE");
    }

    @Test
    void 탈퇴와_재설정_발급이_경합해도_탈퇴뒤_인증행을_남기지_않는다() throws Exception {
        SeededIdentity seeded = insertEmailIdentity("issue-race@example.com", "발급경합");
        String reauthToken = reauthTokenManager.issue(seeded.userId(), Instant.now()).token();

        runConcurrently(
                () -> {
                    passwordResetTransaction.issueIfEmailIdentityExists(
                            seeded.email(),
                            Instant.now());
                    return null;
                },
                () -> {
                    memberDeletionService.delete(seeded.userId(), reauthToken);
                    return null;
                });

        assertDeleted();
    }

    @Test
    void 탈퇴와_재설정_완료가_경합해도_탈퇴뒤_인증행과_세션을_남기지_않는다() throws Exception {
        SeededIdentity seeded = insertEmailIdentity("complete-race@example.com", "완료경합");
        String rawToken = tokenManager.generate();
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, token_hash, attempts, sent_at, expires_at)
                VALUES (?, 'PASSWORD_RESET', ?, 0, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '30 minutes')
                """,
                seeded.email(),
                tokenManager.hash(rawToken));
        String reauthToken = reauthTokenManager.issue(seeded.userId(), Instant.now()).token();

        runConcurrently(
                () -> passwordResetTransaction.reset(
                        tokenManager.hash(rawToken),
                        "changedRun4life2",
                        Instant.now()),
                () -> {
                    memberDeletionService.delete(seeded.userId(), reauthToken);
                    return null;
                });

        assertDeleted();
    }

    private SeededIdentity insertEmailIdentity(String email, String nickname) {
        long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                nickname,
                nickname);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, password_hash,
                    email_verified_at, created_at)
                VALUES (?, 'EMAIL', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                email,
                passwordHasher.hash("oldRun4life1"));
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '14 days', CURRENT_TIMESTAMP)
                """,
                userId,
                UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        return new SeededIdentity(userId, email);
    }

    private void runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> {
                start.await();
                return first.call();
            });
            Future<?> secondResult = executor.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertDeleted() {
        assertThat(count("app_user")).isZero();
        assertThat(count("login_identity")).isZero();
        assertThat(count("email_verification")).isZero();
        assertThat(count("refresh_token")).isZero();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private record SeededIdentity(long userId, String email) {}
}
