package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.auth.application.IssuedTokenPair;
import com.runninggu.server.auth.application.RefreshTokenHasher;
import com.runninggu.server.auth.application.TokenIssuer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AuthSessionApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("accessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @Autowired
    @Qualifier("refreshJwtDecoder")
    private JwtDecoder refreshJwtDecoder;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE email_verification, login_identity, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 가입은_인증행을_즉시삭제하고_약관과_BCrypt_비밀번호와_해시된_세션을_저장한다() throws Exception {
        verifyEmail("runner@example.com");

        JsonNode response = signup(
                "  RUNNER@Example.COM  ",
                "run4life1",
                " 김러너 ",
                true,
                true,
                false);

        assertThat(response.path("user").path("id").asLong()).isPositive();
        assertThat(response.path("user").path("email").asText()).isEqualTo("runner@example.com");
        assertThat(response.path("user").path("nickname").asText()).isEqualTo("김러너");
        assertThat(response.path("user").path("loginProvider").asText()).isEqualTo("EMAIL");

        Map<String, Object> identity = jdbcTemplate.queryForMap(
                "SELECT provider_subject, password_hash, last_login_at FROM login_identity");
        assertThat(identity.get("provider_subject")).isEqualTo("runner@example.com");
        assertThat(identity.get("password_hash").toString()).startsWith("$2");
        assertThat(identity.get("password_hash")).isNotEqualTo("run4life1");
        assertThat(identity.get("last_login_at")).isNotNull();

        List<Map<String, Object>> agreements = jdbcTemplate.queryForList(
                "SELECT agreement_type, version, agreed, changed_at FROM user_agreement ORDER BY agreement_type");
        assertThat(agreements).hasSize(3);
        assertThat(agreements).allSatisfy(row -> assertThat(row.get("version")).isEqualTo("1.0"));
        assertThat(agreements.stream().map(row -> row.get("changed_at")).distinct()).hasSize(1);
        assertThat(agreements).anySatisfy(row -> {
            assertThat(row.get("agreement_type")).isEqualTo("MARKETING");
            assertThat(row.get("agreed")).isEqualTo(false);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification",
                Integer.class)).isZero();

        String rawRefresh = response.path("refreshToken").asText();
        Map<String, Object> storedRefresh = jdbcTemplate.queryForMap(
                "SELECT token_hash, length(token_hash) AS hash_length, revoked_at FROM refresh_token");
        assertThat(storedRefresh.get("token_hash")).isNotEqualTo(rawRefresh);
        assertThat(storedRefresh.get("hash_length")).isEqualTo(64);
        assertThat(storedRefresh.get("revoked_at")).isNull();

        assertTokenClaims(response.path("accessToken").asText(), accessJwtDecoder, "ACCESS", 30);
        assertTokenClaims(rawRefresh, refreshJwtDecoder, "REFRESH", 14 * 24 * 60);
    }

    @Test
    void 가입은_필수동의_비밀번호_인증_중복을_각_계약오류로_거부한다() throws Exception {
        verifyEmail("policy@example.com");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                "policy@example.com", "run4life1", "정책러너", false, true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGREEMENT_REQUIRED"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                "policy@example.com",
                                "a1" + "x".repeat(71),
                                "정책러너",
                                true,
                                true,
                                false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                "missing@example.com", "run4life1", "이력없음", true, true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        insertUnverifiedEmail("unverified@example.com");
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                "unverified@example.com", "run4life1", "미인증", true, true, false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        verifyEmail("expired@example.com");
        jdbcTemplate.update(
                "UPDATE email_verification SET verified_at = CURRENT_TIMESTAMP - INTERVAL '30 minutes' WHERE email = ?",
                "expired@example.com");
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                "expired@example.com", "run4life1", "인증만료", true, true, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));

        signup("policy@example.com", "run4life1", "정책러너", true, true, false);
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                "policy@example.com", "run4life1", "다른닉네임", true, true, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
    }

    @Test
    void 로그인은_새_기기_family를_발급하고_실패시_계정존재를_노출하지_않는다() throws Exception {
        verifyEmail("login@example.com");
        signup("login@example.com", "run4life1", "로그인러너", true, true, false);

        JsonNode login = login("LOGIN@example.com", "run4life1", 200);
        assertThat(login.path("user").path("email").asText()).isEqualTo("login@example.com");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT family_id) FROM refresh_token WHERE revoked_at IS NULL",
                Integer.class)).isEqualTo(2);

        login("login@example.com", "wrong-pass1", 401);
        login("missing@example.com", "wrong-pass1", 401);
        login("not-an-email", "wrong-pass1", 401);
    }

    @Test
    void 회전전_리프레시를_재사용하면_같은_family만_폐기한다() throws Exception {
        verifyEmail("rotate@example.com");
        JsonNode signup = signup(
                "rotate@example.com", "run4life1", "회전러너", true, true, false);
        String oldRefresh = signup.path("refreshToken").asText();
        String otherDeviceRefresh = login("rotate@example.com", "run4life1", 200)
                .path("refreshToken").asText();

        JsonNode rotated = refresh(oldRefresh, 200);
        String rotatedRefresh = rotated.path("refreshToken").asText();
        assertThat(rotatedRefresh).isNotEqualTo(oldRefresh);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NULL",
                Integer.class)).isEqualTo(2);

        refresh(oldRefresh, 401);
        refresh(rotatedRefresh, 401);
        refresh(otherDeviceRefresh, 200);
    }

    @Test
    void 원래_만료시각을_지난_폐기토큰은_재사용탐지로_family를_폐기하지_않는다() throws Exception {
        verifyEmail("expired-history@example.com");
        signup("expired-history@example.com", "run4life1", "만료이력", true, true, false);
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE nickname_key = ?",
                Long.class,
                "만료이력");
        UUID familyId = UUID.randomUUID();
        Instant now = Instant.now();
        IssuedTokenPair expired = tokenIssuer.issue(
                userId,
                familyId,
                now.minus(Duration.ofDays(15)));
        IssuedTokenPair current = tokenIssuer.issue(
                userId,
                familyId,
                now.minusSeconds(1));

        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, revoked_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                userId,
                familyId,
                refreshTokenHasher.hash(expired.refreshToken()),
                Timestamp.from(expired.refreshExpiresAt()),
                Timestamp.from(expired.refreshExpiresAt().minusSeconds(1)),
                Timestamp.from(now.minus(Duration.ofDays(15))));
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId,
                familyId,
                refreshTokenHasher.hash(current.refreshToken()),
                Timestamp.from(current.refreshExpiresAt()),
                Timestamp.from(now.minusSeconds(1)));

        refresh(expired.refreshToken(), 401);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE family_id = ? AND revoked_at IS NULL",
                Integer.class,
                familyId)).isOne();
        refresh(current.refreshToken(), 200);
    }

    @Test
    void 로그아웃은_공개_멱등이고_Access_blacklist를_두지_않는다() throws Exception {
        verifyEmail("logout@example.com");
        JsonNode signup = signup(
                "logout@example.com", "run4life1", "로그아웃러너", true, true, false);
        String accessToken = signup.path("accessToken").asText();
        String refreshToken = signup.path("refreshToken").asText();

        logout(refreshToken).andExpect(status().isNoContent());
        logout(refreshToken).andExpect(status().isNoContent());
        logout("unknown-non-blank-token").andExpect(status().isNoContent());
        refresh(refreshToken, 401);

        mockMvc.perform(get("/api/me/not-implemented")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void Refresh_JWT는_Bearer_Access로_사용할_수_없다() throws Exception {
        verifyEmail("filter@example.com");
        JsonNode signup = signup(
                "filter@example.com", "run4life1", "필터러너", true, true, false);

        mockMvc.perform(get("/api/me/not-implemented")
                        .header("Authorization", "Bearer " + signup.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private JsonNode signup(
            String email,
            String password,
            String nickname,
            boolean tos,
            boolean privacy,
            boolean marketing) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, password, nickname, tos, privacy, marketing)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode login(String email, String password, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password))))
                .andExpect(status().is(expectedStatus))
                .andExpect(expectedStatus == 401
                        ? jsonPath("$.code").value("LOGIN_FAILED")
                        : jsonPath("$.accessToken").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", refreshToken))))
                .andExpect(status().is(expectedStatus))
                .andExpect(expectedStatus == 401
                        ? jsonPath("$.code").value("INVALID_REFRESH_TOKEN")
                        : jsonPath("$.accessToken").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private org.springframework.test.web.servlet.ResultActions logout(String refreshToken)
            throws Exception {
        return mockMvc.perform(post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "refreshToken", refreshToken))));
    }

    private String signupJson(
            String email,
            String password,
            String nickname,
            boolean tos,
            boolean privacy,
            boolean marketing) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", password,
                "nickname", nickname,
                "agreements", Map.of(
                        "tos", tos,
                        "privacy", privacy,
                        "marketing", marketing)));
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

    private void insertUnverifiedEmail(String email) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, code_hash, attempts, sent_at, expires_at)
                VALUES (?, 'SIGNUP', '$2a$10$test-verification-hash', 0,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """,
                email);
    }

    private void assertTokenClaims(
            String rawToken,
            JwtDecoder decoder,
            String type,
            long expectedMinutes) {
        Jwt jwt = decoder.decode(rawToken);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("runninggu");
        assertThat(jwt.getAudience()).containsExactly("runninggu-api");
        assertThat(jwt.getClaimAsString("type")).isEqualTo(type);
        assertThat(jwt.getSubject()).isEqualTo("1");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isNotNull();
        long actualSeconds = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds();
        assertThat(actualSeconds).isEqualTo(Duration.ofMinutes(expectedMinutes).toSeconds());
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }
}
