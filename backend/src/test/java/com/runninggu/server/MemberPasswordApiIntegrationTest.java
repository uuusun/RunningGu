package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.auth.application.TokenIssuer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MemberPasswordApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenIssuer tokenIssuer;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE email_verification, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 비밀번호를_바꾸면_기존_모든_Refresh를_폐기하고_새_세션을_발급한다()
            throws Exception {
        JsonNode signup = signup("runner@example.com", "run4life1", "변경러너");
        JsonNode secondDevice = login("runner@example.com", "run4life1", 200);
        String firstRefresh = signup.path("refreshToken").asText();
        String secondRefresh = secondDevice.path("refreshToken").asText();

        JsonNode changed = changePassword(
                signup.path("accessToken").asText(),
                "run4life1",
                "newRun4life2",
                200);
        String newRefresh = changed.path("refreshToken").asText();

        assertThat(changed.path("accessToken").asText()).isNotBlank();
        assertThat(newRefresh).isNotBlank().isNotIn(firstRefresh, secondRefresh);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NOT NULL",
                Integer.class)).isEqualTo(2);
        Map<String, Object> active = jdbcTemplate.queryForMap(
                "SELECT token_hash, length(token_hash) AS hash_length "
                        + "FROM refresh_token WHERE revoked_at IS NULL");
        assertThat(active.get("token_hash")).isNotEqualTo(newRefresh);
        assertThat(active.get("hash_length")).isEqualTo(64);

        refresh(firstRefresh, 401);
        refresh(secondRefresh, 401);
        refresh(newRefresh, 200);
        login("runner@example.com", "run4life1", 401);
        login("runner@example.com", "newRun4life2", 200);
    }

    @Test
    void 현재_비밀번호_불일치와_새_비밀번호_정책오류는_기존_상태를_유지한다()
            throws Exception {
        JsonNode signup = signup("runner@example.com", "run4life1", "검증러너");
        String accessToken = signup.path("accessToken").asText();
        String refreshToken = signup.path("refreshToken").asText();
        String passwordHash = storedPasswordHash();

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("wrongPass1", "newRun4life2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("run4life1", "short1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

        assertThat(storedPasswordHash()).isEqualTo(passwordHash);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE revoked_at IS NULL",
                Integer.class)).isEqualTo(1);
        refresh(refreshToken, 200);
    }

    @Test
    void KAKAO_계정과_인증되지_않은_요청을_거부한다() throws Exception {
        long userId = seedKakaoUser();
        String accessToken = tokenIssuer.issue(
                        userId, UUID.randomUUID(), Instant.now().minusSeconds(1))
                .accessToken();

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("run4life1", "newRun4life2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IDENTITY_REQUIRED"));

        mockMvc.perform(put("/api/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson("run4life1", "newRun4life2")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 비밀번호_변경은_필수필드를_검증한다() throws Exception {
        JsonNode signup = signup("runner@example.com", "run4life1", "필드러너");

        mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(signup.path("accessToken").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private JsonNode signup(String email, String password, String nickname) throws Exception {
        verifyEmail(email);
        MvcResult result = mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "nickname", nickname,
                                "agreements", Map.of(
                                        "tos", true,
                                        "privacy", true,
                                        "marketing", false)))))
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
                .andReturn();
        return readBody(result);
    }

    private JsonNode refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", refreshToken))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return readBody(result);
    }

    private JsonNode changePassword(
            String accessToken,
            String currentPassword,
            String newPassword,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(put("/api/me/password")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordJson(currentPassword, newPassword)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return readBody(result);
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        byte[] body = result.getResponse().getContentAsByteArray();
        return body.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private String passwordJson(String currentPassword, String newPassword) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "currentPassword", currentPassword,
                "newPassword", newPassword));
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

    private long seedKakaoUser() {
        long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES ('카카오러너', '카카오러너', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, email_snapshot,
                    password_hash, email_verified_at, created_at, last_login_at)
                VALUES (?, 'KAKAO', 'kakao-100', NULL, NULL, NULL,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId);
        return userId;
    }

    private String storedPasswordHash() {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM login_identity WHERE provider = 'EMAIL'",
                String.class);
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
