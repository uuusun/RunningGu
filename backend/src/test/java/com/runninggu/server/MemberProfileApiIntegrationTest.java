package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.auth.application.TokenIssuer;
import com.runninggu.server.auth.domain.AgreementType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
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

@SpringBootTest
@AutoConfigureMockMvc
class MemberProfileApiIntegrationTest extends PostgreSqlContainerSupport {

    private static final Instant CREATED_AT = Instant.parse("2026-08-23T05:00:00Z");

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
        jdbcTemplate.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void EMAIL_프로필은_현재_약관과_가입시각을_반환하고_다른_사용자를_노출하지_않는다()
            throws Exception {
        long userId = seedEmailUser(
                "runner@example.com", "김러너", false);
        seedEmailUser("other@example.com", "다른러너", true);

        mockMvc.perform(get("/api/me")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value("runner@example.com"))
                .andExpect(jsonPath("$.nickname").value("김러너"))
                .andExpect(jsonPath("$.loginProvider").value("EMAIL"))
                .andExpect(jsonPath("$.agreements.tos").value(true))
                .andExpect(jsonPath("$.agreements.privacy").value(true))
                .andExpect(jsonPath("$.agreements.marketing").value(false))
                .andExpect(jsonPath("$.createdAt").value("2026-08-23T05:00:00Z"));
    }

    @Test
    void KAKAO_프로필은_이메일_스냅샷이_없어도_null_키를_유지한다() throws Exception {
        long userId = seedKakaoUser("kakao-100", null, "카카오러너", true);

        mockMvc.perform(get("/api/me")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.loginProvider").value("KAKAO"))
                .andExpect(jsonPath("$.agreements.marketing").value(true));
    }

    @Test
    void 닉네임은_정규화해_변경하고_자기자신을_제외한_중복과_형식오류를_거부한다()
            throws Exception {
        long userId = seedEmailUser("runner@example.com", "Runner", false);
        seedEmailUser("other@example.com", "Other", false);

        mockMvc.perform(patch("/api/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "  새닉네임  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"));

        Map<String, Object> changed = jdbcTemplate.queryForMap(
                "SELECT nickname, nickname_key FROM app_user WHERE id = ?",
                userId);
        assertThat(changed.get("nickname")).isEqualTo("새닉네임");
        assertThat(changed.get("nickname_key")).isEqualTo("새닉네임");

        mockMvc.perform(patch("/api/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "OTHER"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));

        mockMvc.perform(patch("/api/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "한"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 마케팅_동의는_변경시에만_append_only_이력을_추가하고_같은_요청은_멱등이다()
            throws Exception {
        long userId = seedEmailUser("runner@example.com", "동의러너", false);
        long otherUserId = seedEmailUser("other@example.com", "다른동의", false);

        mockMvc.perform(patch("/api/me/agreements")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("marketing", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreements.tos").value(true))
                .andExpect(jsonPath("$.agreements.privacy").value(true))
                .andExpect(jsonPath("$.agreements.marketing").value(true));

        assertThat(agreementCount(userId)).isEqualTo(4);
        assertThat(latestMarketing(userId)).isTrue();
        assertThat(latestMarketingVersion(userId)).isEqualTo("1.0");

        mockMvc.perform(patch("/api/me/agreements")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("marketing", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreements.marketing").value(true));

        assertThat(agreementCount(userId)).isEqualTo(4);
        assertThat(agreementCount(otherUserId)).isEqualTo(3);
        assertThat(latestMarketing(otherUserId)).isFalse();
    }

    @Test
    void 프로필_API는_인증과_필수_요청필드를_검증한다() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(patch("/api/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", "새닉네임"))))
                .andExpect(status().isUnauthorized());

        long userId = seedEmailUser("runner@example.com", "검증러너", false);
        mockMvc.perform(patch("/api/me/agreements")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private long seedEmailUser(String email, String nickname, boolean marketing) {
        long userId = insertUser(nickname);
        OffsetDateTime timestamp = timestamp();
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, email_snapshot, password_hash,
                    email_verified_at, created_at, last_login_at)
                VALUES (?, 'EMAIL', ?, NULL, 'test-password-hash', ?, ?, ?)
                """,
                userId,
                email,
                timestamp,
                timestamp,
                timestamp);
        insertAgreements(userId, marketing);
        return userId;
    }

    private long seedKakaoUser(
            String kakaoSubject,
            String emailSnapshot,
            String nickname,
            boolean marketing) {
        long userId = insertUser(nickname);
        OffsetDateTime timestamp = timestamp();
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, email_snapshot, password_hash,
                    email_verified_at, created_at, last_login_at)
                VALUES (?, 'KAKAO', ?, ?, NULL, NULL, ?, ?)
                """,
                userId,
                kakaoSubject,
                emailSnapshot,
                timestamp,
                timestamp);
        insertAgreements(userId, marketing);
        return userId;
    }

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                nickname,
                nickname.toLowerCase(Locale.ROOT),
                timestamp(),
                timestamp());
    }

    private void insertAgreements(long userId, boolean marketing) {
        for (AgreementType type : AgreementType.values()) {
            boolean agreed = type == AgreementType.MARKETING ? marketing : true;
            jdbcTemplate.update(
                    """
                    INSERT INTO user_agreement(
                        user_id, agreement_type, version, agreed, changed_at)
                    VALUES (?, ?, '1.0', ?, ?)
                    """,
                    userId,
                    type.name(),
                    agreed,
                    timestamp());
        }
    }

    private int agreementCount(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_agreement WHERE user_id = ?",
                Integer.class,
                userId);
    }

    private boolean latestMarketing(long userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT agreed
                FROM user_agreement
                WHERE user_id = ? AND agreement_type = 'MARKETING'
                ORDER BY changed_at DESC, id DESC
                LIMIT 1
                """,
                Boolean.class,
                userId);
    }

    private String latestMarketingVersion(long userId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM user_agreement
                WHERE user_id = ? AND agreement_type = 'MARKETING'
                ORDER BY changed_at DESC, id DESC
                LIMIT 1
                """,
                String.class,
                userId);
    }

    private String bearer(long userId) {
        String token = tokenIssuer.issue(
                        userId,
                        UUID.randomUUID(),
                        Instant.now().minusSeconds(1))
                .accessToken();
        return "Bearer " + token;
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private OffsetDateTime timestamp() {
        return OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC);
    }
}
