package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.auth.application.EmailVerificationService;
import com.runninggu.server.auth.application.VerificationCodeGenerator;
import com.runninggu.server.auth.application.VerificationCodeHasher;
import com.runninggu.server.auth.application.VerificationMailSender;
import com.runninggu.server.auth.infrastructure.MailDeliveryException;
import com.runninggu.server.common.error.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
@Import(AuthVerificationApiIntegrationTest.AuthTestConfig.class)
class AuthVerificationApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CapturingMailSender mailSender;

    @Autowired
    private SequenceCodeGenerator codeGenerator;

    @Autowired
    private VerificationCodeHasher codeHasher;

    @Autowired
    private EmailVerificationService verificationService;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE email_verification, login_identity, app_user RESTART IDENTITY CASCADE");
        mailSender.reset();
        codeGenerator.reset();
    }

    @Test
    void 이메일_중복확인은_정규화한_EMAIL_로그인_주체만_조회한다() throws Exception {
        insertEmailUser("email-user", "runner@example.com");
        insertKakaoUser("kakao-user", "kakao-subject", "kakao-only@example.com");

        mockMvc.perform(get("/api/auth/email/exists")
                        .param("email", "  RUNNER@Example.COM  ")
                        .with(request -> remoteAddress(request, "192.0.2.1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        mockMvc.perform(get("/api/auth/email/exists")
                        .param("email", "kakao-only@example.com")
                        .with(request -> remoteAddress(request, "192.0.2.2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));
    }

    @Test
    void 닉네임_중복확인은_ASCII_영문_대소문자를_같게_취급한다() throws Exception {
        insertUser("Run닝Gu", "run닝gu");

        mockMvc.perform(get("/api/auth/nickname/exists")
                        .param("nickname", "  RUN닝GU  ")
                        .with(request -> remoteAddress(request, "192.0.2.3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    void 잘못된_중복확인_입력은_VALIDATION_FAILED다() throws Exception {
        mockMvc.perform(get("/api/auth/email/exists")
                        .param("email", "not-an-email")
                        .with(request -> remoteAddress(request, "192.0.2.4")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/auth/nickname/exists")
                        .param("nickname", "한")
                        .with(request -> remoteAddress(request, "192.0.2.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 동일_정규화_대상_중복확인은_분당_5회_다음부터_RATE_LIMITED다() throws Exception {
        for (int count = 0; count < 5; count++) {
            int requestIndex = count;
            mockMvc.perform(get("/api/auth/email/exists")
                            .param("email", count % 2 == 0
                                    ? "LIMITED@example.com"
                                    : " limited@EXAMPLE.COM ")
                            .with(request -> remoteAddress(request, "198.51.100." + requestIndex)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/auth/email/exists")
                        .param("email", "limited@example.com")
                        .with(request -> remoteAddress(request, "198.51.100.99")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void 코드를_발송하면_정규화한_이메일과_BCrypt_해시만_저장한다() throws Exception {
        sendCode("  RUNNER@Example.COM  ")
                .andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT email, code_hash, attempts, verified_at FROM email_verification");
        String rawCode = mailSender.lastCode("runner@example.com");

        assertThat(row.get("email")).isEqualTo("runner@example.com");
        assertThat(row.get("code_hash")).isNotEqualTo(rawCode);
        assertThat(codeHasher.matches(rawCode, row.get("code_hash").toString())).isTrue();
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("verified_at")).isNull();
    }

    @Test
    void 가입된_이메일에는_발송하지_않고_EMAIL_DUPLICATED를_반환한다() throws Exception {
        insertEmailUser("existing", "existing@example.com");

        sendCode("EXISTING@example.com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));

        assertThat(mailSender.sentCount()).isZero();
        assertThat(countVerificationRows()).isZero();
    }

    @Test
    void 발송_후_60초_안의_재요청은_SEND_COOLDOWN이다() throws Exception {
        sendCode("cooldown@example.com").andExpect(status().isNoContent());

        sendCode("cooldown@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SEND_COOLDOWN"));

        assertThat(mailSender.sentCount()).isEqualTo(1);
    }

    @Test
    void 재발송하면_이전_코드와_인증상태를_무효화한다() throws Exception {
        sendCode("resend@example.com").andExpect(status().isNoContent());
        String oldCode = mailSender.lastCode("resend@example.com");
        verifyCode("resend@example.com", oldCode).andExpect(status().isOk());
        moveSentAtOutsideCooldown("resend@example.com");

        sendCode("resend@example.com").andExpect(status().isNoContent());
        String newCode = mailSender.lastCode("resend@example.com");

        assertThat(newCode).isNotEqualTo(oldCode);
        verifyCode("resend@example.com", oldCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        verifyCode("resend@example.com", newCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    void 인증된_같은_코드는_30분간_멱등이고_다른_코드는_상태를_바꾸지_않는다() throws Exception {
        sendCode("idempotent@example.com").andExpect(status().isNoContent());
        String code = mailSender.lastCode("idempotent@example.com");

        verifyCode("idempotent@example.com", code).andExpect(status().isOk());
        verifyCode("idempotent@example.com", code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));
        verifyCode("idempotent@example.com", "999999")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(attempts("idempotent@example.com")).isZero();
    }

    @Test
    void 인증_완료_후_30분이_지나면_CODE_EXPIRED다() throws Exception {
        sendCode("verified-expired@example.com").andExpect(status().isNoContent());
        String code = mailSender.lastCode("verified-expired@example.com");
        verifyCode("verified-expired@example.com", code).andExpect(status().isOk());
        jdbcTemplate.update(
                "UPDATE email_verification SET verified_at = CURRENT_TIMESTAMP - INTERVAL '30 minutes' WHERE email = ?",
                "verified-expired@example.com");

        verifyCode("verified-expired@example.com", code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    }

    @Test
    void 형식이_아닌_코드는_실패횟수를_차감하지_않는다() throws Exception {
        sendCode("malformed@example.com").andExpect(status().isNoContent());

        verifyCode("malformed@example.com", "12A")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(attempts("malformed@example.com")).isZero();
    }

    @Test
    void 다섯번째_불일치는_횟수를_보존하고_TOO_MANY_ATTEMPTS다() throws Exception {
        sendCode("attempts@example.com").andExpect(status().isNoContent());

        for (int count = 0; count < 4; count++) {
            verifyCode("attempts@example.com", "90000" + count)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        }
        verifyCode("attempts@example.com", "900004")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        verifyCode("attempts@example.com", mailSender.lastCode("attempts@example.com"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));

        assertThat(attempts("attempts@example.com")).isEqualTo(5);
    }

    @Test
    void 코드_이력이_없거나_10분이_지나면_CODE_EXPIRED다() throws Exception {
        verifyCode("missing@example.com", "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        sendCode("expired@example.com").andExpect(status().isNoContent());
        jdbcTemplate.update(
                "UPDATE email_verification SET expires_at = CURRENT_TIMESTAMP WHERE email = ?",
                "expired@example.com");

        verifyCode("expired@example.com", mailSender.lastCode("expired@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
    }

    @Test
    void SMTP_실패는_502이고_코드나_쿨다운을_남기지_않아_즉시_재시도할_수_있다() throws Exception {
        mailSender.failNext();

        sendCode("smtp-fail@example.com")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
        assertThat(countVerificationRows()).isZero();

        sendCode("smtp-fail@example.com").andExpect(status().isNoContent());
        assertThat(countVerificationRows()).isEqualTo(1);
    }

    @Test
    void 동시에_첫_발송을_요청해도_메일은_한번만_보낸다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int count = 0; count < 2; count++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        verificationService.sendSignupCode("concurrent-send@example.com");
                        return "SUCCESS";
                    } catch (ApiException exception) {
                        return exception.errorCode().name();
                    }
                }));
            }
            start.countDown();

            List<String> outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "SEND_COOLDOWN");
            assertThat(mailSender.sentCount()).isEqualTo(1);
            assertThat(countVerificationRows()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 동시_오답도_직렬화되어_실패횟수가_유실되지_않는다() throws Exception {
        verificationService.sendSignupCode("concurrent-verify@example.com");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int count = 0; count < 6; count++) {
                String wrongCode = "80000" + count;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        verificationService.verifySignupCode(
                                "concurrent-verify@example.com",
                                wrongCode);
                        return "SUCCESS";
                    } catch (ApiException exception) {
                        return exception.errorCode().name();
                    }
                }));
            }
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }
            assertThat(Collections.frequency(outcomes, "INVALID_CODE")).isEqualTo(4);
            assertThat(Collections.frequency(outcomes, "TOO_MANY_ATTEMPTS")).isEqualTo(2);
            assertThat(attempts("concurrent-verify@example.com")).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    private org.springframework.test.web.servlet.ResultActions sendCode(String email)
            throws Exception {
        return mockMvc.perform(post("/api/auth/email/send-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions verifyCode(String email, String code)
            throws Exception {
        return mockMvc.perform(post("/api/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"));
    }

    private org.springframework.mock.web.MockHttpServletRequest remoteAddress(
            org.springframework.mock.web.MockHttpServletRequest request,
            String address) {
        request.setRemoteAddr(address);
        return request;
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

    private void insertEmailUser(String nickname, String email) {
        long userId = insertUser(nickname, nickname.toLowerCase());
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, password_hash,
                    email_verified_at, created_at)
                VALUES (?, 'EMAIL', ?, 'password-hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                email);
    }

    private void insertKakaoUser(String nickname, String subject, String emailSnapshot) {
        long userId = insertUser(nickname, nickname.toLowerCase());
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

    private void moveSentAtOutsideCooldown(String email) {
        jdbcTemplate.update(
                "UPDATE email_verification SET sent_at = CURRENT_TIMESTAMP - INTERVAL '61 seconds' WHERE email = ?",
                email);
    }

    private int attempts(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT attempts FROM email_verification WHERE email = ?",
                Integer.class,
                email);
    }

    private int countVerificationRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification",
                Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AuthTestConfig {

        @Bean
        @Primary
        CapturingMailSender testMailSender() {
            return new CapturingMailSender();
        }

        @Bean
        @Primary
        SequenceCodeGenerator testCodeGenerator() {
            return new SequenceCodeGenerator();
        }
    }

    static class CapturingMailSender implements VerificationMailSender {
        private final Map<String, String> lastCodes = new ConcurrentHashMap<>();
        private final AtomicInteger sentCount = new AtomicInteger();
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void sendSignupCode(String recipient, String code) {
            if (failNext.compareAndSet(true, false)) {
                throw new MailDeliveryException("test smtp failure");
            }
            lastCodes.put(recipient, code);
            sentCount.incrementAndGet();
        }

        String lastCode(String recipient) {
            return lastCodes.get(recipient);
        }

        int sentCount() {
            return sentCount.get();
        }

        void failNext() {
            failNext.set(true);
        }

        void reset() {
            lastCodes.clear();
            sentCount.set(0);
            failNext.set(false);
        }
    }

    static class SequenceCodeGenerator implements VerificationCodeGenerator {
        private final AtomicInteger sequence = new AtomicInteger(123_456);

        @Override
        public String generate() {
            return "%06d".formatted(sequence.getAndIncrement());
        }

        void reset() {
            sequence.set(123_456);
        }
    }
}
