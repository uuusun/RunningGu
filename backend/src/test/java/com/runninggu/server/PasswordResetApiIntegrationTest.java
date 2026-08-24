package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.auth.application.PasswordHasher;
import com.runninggu.server.auth.application.PasswordResetTokenManager;
import com.runninggu.server.auth.application.VerificationMailSender;
import com.runninggu.server.auth.infrastructure.MailDeliveryException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PasswordResetApiIntegrationTest.PasswordResetTestConfig.class)
class PasswordResetApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private PasswordResetTokenManager tokenManager;

    @Autowired
    private CapturingMailSender mailSender;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE refresh_token, email_verification, login_identity, app_user "
                        + "RESTART IDENTITY CASCADE");
        mailSender.reset();
    }

    @Test
    void 재설정_링크로_비밀번호를_한번만_바꾸고_모든_세션을_폐기한다() throws Exception {
        long userId = insertEmailUser(
                "reset-runner",
                "reset-runner@example.com",
                "oldRun4life1");
        insertRefreshToken(userId, "a".repeat(64));
        insertRefreshToken(userId, "b".repeat(64));

        requestReset("  RESET-RUNNER@Example.COM  ")
                .andExpect(status().isAccepted());

        String rawToken = mailSender.lastToken("reset-runner@example.com");
        Map<String, Object> verification = jdbcTemplate.queryForMap(
                """
                SELECT email, code_hash, token_hash, expires_at, consumed_at
                FROM email_verification
                WHERE purpose = 'PASSWORD_RESET'
                """);
        assertThat(rawToken).isNotBlank();
        assertThat(verification.get("email")).isEqualTo("reset-runner@example.com");
        assertThat(verification.get("code_hash")).isNull();
        assertThat(verification.get("token_hash")).isEqualTo(tokenManager.hash(rawToken));
        assertThat(verification.get("token_hash")).isNotEqualTo(rawToken);
        assertThat(verification.get("consumed_at")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT expires_at BETWEEN
                            sent_at + INTERVAL '29 minutes 59 seconds'
                            AND sent_at + INTERVAL '30 minutes 1 second'
                        FROM email_verification
                        """,
                        Boolean.class))
                .isTrue();

        resetPassword(rawToken, "newRun4life2")
                .andExpect(status().isNoContent());

        String changedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM login_identity WHERE user_id = ?",
                String.class,
                userId);
        assertThat(passwordHasher.matches("newRun4life2", changedHash)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NOT NULL",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT consumed_at IS NOT NULL FROM email_verification",
                        Boolean.class))
                .isTrue();

        resetPassword(rawToken, "otherRun4life3")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void 약한_비밀번호는_토큰을_소비하지_않고_만료_토큰은_거부한다() throws Exception {
        long weakUserId = insertEmailUser(
                "weak-reset",
                "weak-reset@example.com",
                "oldRun4life1");
        requestReset("weak-reset@example.com").andExpect(status().isAccepted());
        String weakToken = mailSender.lastToken("weak-reset@example.com");

        resetPassword(weakToken, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT consumed_at IS NULL FROM email_verification WHERE email = ?",
                        Boolean.class,
                        "weak-reset@example.com"))
                .isTrue();

        resetPassword(weakToken, "validRun4life2")
                .andExpect(status().isNoContent());
        String changedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM login_identity WHERE user_id = ?",
                String.class,
                weakUserId);
        assertThat(passwordHasher.matches("validRun4life2", changedHash)).isTrue();

        insertEmailUser("expired", "expired-reset@example.com", "oldRun4life1");
        requestReset("expired-reset@example.com").andExpect(status().isAccepted());
        String expiredToken = mailSender.lastToken("expired-reset@example.com");
        jdbcTemplate.update(
                "UPDATE email_verification SET expires_at = CURRENT_TIMESTAMP "
                        + "WHERE email = ?",
                "expired-reset@example.com");

        resetPassword(expiredToken, "newRun4life2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void 미가입과_카카오_이메일도_같은_응답과_쿨다운을_주고_DB에는_남기지_않는다()
            throws Exception {
        insertKakaoUser("kakao-reset", "429001", "kakao-reset@example.com");

        requestReset("unknown-reset@example.com").andExpect(status().isAccepted());
        requestReset("kakao-reset@example.com").andExpect(status().isAccepted());
        assertThat(mailSender.sentCount()).isZero();
        assertThat(verificationCount()).isZero();

        requestReset(" UNKNOWN-RESET@example.com ")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEND_COOLDOWN"));
        requestReset("KAKAO-RESET@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEND_COOLDOWN"));
        assertThat(verificationCount()).isZero();
    }

    @Test
    void 가입된_이메일의_재요청도_60초_쿨다운이다() throws Exception {
        insertEmailUser("cooldown", "cooldown-reset@example.com", "oldRun4life1");

        requestReset("cooldown-reset@example.com").andExpect(status().isAccepted());
        requestReset("COOLDOWN-RESET@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEND_COOLDOWN"));

        assertThat(mailSender.sentCount()).isEqualTo(1);
        assertThat(verificationCount()).isEqualTo(1);
    }

    @Test
    void 메일_실패는_계정_존재를_노출하지_않고_즉시_재시도할_수_있다() throws Exception {
        insertEmailUser("mail-fail", "mail-fail-reset@example.com", "oldRun4life1");
        mailSender.failNext();

        requestReset("mail-fail-reset@example.com")
                .andExpect(status().isAccepted());
        assertThat(verificationCount()).isZero();

        requestReset("mail-fail-reset@example.com")
                .andExpect(status().isAccepted());
        assertThat(mailSender.sentCount()).isEqualTo(1);
        assertThat(verificationCount()).isEqualTo(1);
    }

    @Test
    void 재설정_API_입력과_공개_웹_페이지를_검증한다() throws Exception {
        requestReset("not-an-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        resetPassword("unknown-token", "newRun4life2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));

        mockMvc.perform(get("/reset-password").param("token", "<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/api/auth/password/reset")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<script>alert(1)</script>"))));
    }

    private org.springframework.test.web.servlet.ResultActions requestReset(String email)
            throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions resetPassword(
            String token,
            String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\""
                        + newPassword + "\"}"));
    }

    private long insertEmailUser(String nickname, String email, String password) {
        long userId = insertUser(nickname, nickname);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, password_hash,
                    email_verified_at, created_at)
                VALUES (?, 'EMAIL', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                email,
                passwordHasher.hash(password));
        return userId;
    }

    private void insertKakaoUser(String nickname, String subject, String emailSnapshot) {
        long userId = insertUser(nickname, nickname);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, email_snapshot, created_at)
                VALUES (?, 'KAKAO', ?, ?, CURRENT_TIMESTAMP)
                """,
                userId,
                subject,
                emailSnapshot);
    }

    private long insertUser(String nickname, String nicknameKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                nickname,
                nicknameKey);
    }

    private void insertRefreshToken(long userId, String tokenHash) {
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP)
                """,
                userId,
                UUID.randomUUID(),
                tokenHash);
    }

    private int verificationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification",
                Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PasswordResetTestConfig {

        @Bean
        @Primary
        CapturingMailSender passwordResetMailSender() {
            return new CapturingMailSender();
        }
    }

    static class CapturingMailSender implements VerificationMailSender {
        private final Map<String, String> tokens = new ConcurrentHashMap<>();
        private final AtomicInteger sentCount = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void sendSignupCode(String recipient, String code) {
            throw new UnsupportedOperationException("이 테스트에서는 사용하지 않습니다.");
        }

        @Override
        public void sendPasswordResetLink(String recipient, String rawToken) {
            if (failNext.compareAndSet(true, false)) {
                throw new MailDeliveryException("test smtp failure");
            }
            tokens.put(recipient, rawToken);
            sentCount.incrementAndGet();
        }

        String lastToken(String recipient) {
            return tokens.get(recipient);
        }

        int sentCount() {
            return sentCount.get();
        }

        void failNext() {
            failNext.set(true);
        }

        void reset() {
            tokens.clear();
            sentCount.set(0);
            failNext.set(false);
        }
    }
}
