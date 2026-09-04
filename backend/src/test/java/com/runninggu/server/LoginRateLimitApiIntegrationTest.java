package com.runninggu.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LoginRateLimitApiIntegrationTest extends PostgreSqlContainerSupport {

    private static final String RATE_LIMIT_DETAIL =
            "로그인 시도가 많아요. 잠시 후 다시 시도해 주세요.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE email_verification, login_identity, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 동일_IP의_31번째_로그인과_임의_X_Forwarded_For_우회를_차단한다() throws Exception {
        for (int count = 0; count < 30; count++) {
            login(
                    "ip-" + count + "@example.com",
                    "wrong-pass1",
                    "192.0.2.10",
                    "198.51.100." + count,
                    401,
                    "LOGIN_FAILED");
        }

        login(
                "ip-last@example.com",
                "wrong-pass1",
                "192.0.2.10",
                "203.0.113.99",
                429,
                "RATE_LIMITED");
    }

    @Test
    void 빈_본문도_IP_로그인_창에_기록한다() throws Exception {
        for (int count = 0; count < 30; count++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(request -> {
                                request.setRemoteAddr("192.0.2.20");
                                return request;
                            }))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.20");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.detail").value(RATE_LIMIT_DETAIL));
    }

    @Test
    void 존재여부와_무관하게_같은_이메일의_5회_실패_뒤_RATE_LIMITED다() throws Exception {
        for (int count = 0; count < 5; count++) {
            login(
                    count % 2 == 0
                            ? "  MISSING-RATE@Example.COM  "
                            : "missing-rate@example.com",
                    "wrong-pass1",
                    "198.51.100." + count,
                    null,
                    401,
                    "LOGIN_FAILED");
        }

        login(
                "Missing-Rate@Example.com",
                "wrong-pass1",
                "198.51.100.99",
                null,
                429,
                "RATE_LIMITED");

        signup("known-rate@example.com", "run4life1", "제한러너");
        for (int count = 0; count < 5; count++) {
            login(
                    "known-rate@example.com",
                    "wrong-pass1",
                    "198.51.101." + count,
                    null,
                    401,
                    "LOGIN_FAILED");
        }
        login(
                "known-rate@example.com",
                "wrong-pass1",
                "198.51.101.99",
                null,
                429,
                "RATE_LIMITED");
    }

    @Test
    void 로그인_성공은_이메일_실패창을_초기화한다() throws Exception {
        signup("reset-rate@example.com", "run4life1", "초기화러너");
        for (int count = 0; count < 3; count++) {
            login(
                    "reset-rate@example.com",
                    "wrong-pass1",
                    "198.51.102." + count,
                    null,
                    401,
                    "LOGIN_FAILED");
        }
        login(
                "reset-rate@example.com",
                "run4life1",
                "198.51.102.10",
                null,
                200,
                null);

        for (int count = 0; count < 5; count++) {
            login(
                    "reset-rate@example.com",
                    "wrong-pass1",
                    "198.51.103." + count,
                    null,
                    401,
                    "LOGIN_FAILED");
        }
        login(
                "reset-rate@example.com",
                "wrong-pass1",
                "198.51.103.99",
                null,
                429,
                "RATE_LIMITED");
    }

    @Test
    void 형식이_잘못된_이메일은_이메일_창에_기록하지_않는다() throws Exception {
        for (int count = 0; count < 6; count++) {
            login(
                    "not-an-email",
                    "wrong-pass1",
                    "203.0.113." + count,
                    null,
                    401,
                    "LOGIN_FAILED");
        }
    }

    private void login(
            String email,
            String password,
            String remoteAddr,
            String forwardedFor,
            int expectedStatus,
            String expectedCode) throws Exception {
        var request = post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "password", password)))
                .with(mockRequest -> {
                    mockRequest.setRemoteAddr(remoteAddr);
                    return mockRequest;
                });
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }

        var result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 200) {
            result.andExpect(jsonPath("$.accessToken").isString());
        } else {
            result.andExpect(jsonPath("$.code").value(expectedCode));
        }
        if (expectedStatus == 429) {
            result.andExpect(jsonPath("$.detail").value(RATE_LIMIT_DETAIL));
        }
    }

    private void signup(String email, String password, String nickname) throws Exception {
        verifyEmail(email);
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "nickname", nickname,
                                "ageOver14", true,
                                "agreements", Map.of(
                                        "tos", true,
                                        "privacy", true,
                                        "marketing", false)))))
                .andExpect(status().isCreated());
    }

    private void verifyEmail(String email) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, code_hash, attempts, sent_at, expires_at, verified_at)
                VALUES (?, 'SIGNUP', '$2a$10$test-verification-hash', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes', CURRENT_TIMESTAMP)
                """,
                email);
    }
}
