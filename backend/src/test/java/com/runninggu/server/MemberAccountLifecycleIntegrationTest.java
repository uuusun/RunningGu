package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.auth.application.IssuedTokenPair;
import com.runninggu.server.auth.application.KakaoUserInfoException;
import com.runninggu.server.auth.application.KakaoUserInfoException.Reason;
import com.runninggu.server.auth.application.KakaoUserInfoProvider;
import com.runninggu.server.auth.application.KakaoUserProfile;
import com.runninggu.server.auth.application.PasswordHasher;
import com.runninggu.server.auth.application.RefreshTokenHasher;
import com.runninggu.server.auth.application.TokenIssuer;
import com.runninggu.server.member.application.ReauthTokenManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class MemberAccountLifecycleIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private ReauthTokenManager reauthTokenManager;

    @MockitoBean
    private KakaoUserInfoProvider kakaoUserInfoProvider;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE email_verification, contest, app_user "
                        + "RESTART IDENTITY CASCADE");
        reset(kakaoUserInfoProvider);
    }

    @Test
    void EMAIL_재인증_후_회원과_모든_종속정보를_삭제한다() throws Exception {
        SeededUser user = seedEmailUser(
                "delete-email@example.com",
                "삭제러너",
                "run4life1");
        long contestId = seedContest();
        seedDependentData(user.userId(), contestId, user.email());

        JsonNode reauth = reauth(
                user.accessToken(),
                Map.of("provider", "EMAIL", "password", "run4life1"),
                200);
        assertThat(reauth.path("reauthToken").asText()).isNotBlank();
        assertThat(reauth.path("expiresInSec").asLong()).isEqualTo(300);
        assertThat(reauthTokenManager.decodeUserId(reauth.path("reauthToken").asText()))
                .isEqualTo(user.userId());

        mockMvc.perform(delete("/api/me")
                        .header("Authorization", bearer(user.accessToken()))
                        .header("X-Reauth-Token", reauth.path("reauthToken").asText()))
                .andExpect(status().isNoContent());

        for (String table : new String[] {
            "app_user",
            "login_identity",
            "user_agreement",
            "refresh_token",
            "favorite",
            "itinerary",
            "saved_course",
            "email_verification"
        }) {
            assertThat(count(table)).as(table).isZero();
        }
        assertThat(count("contest")).isOne();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", user.refreshToken()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mockMvc.perform(get("/api/me")
                        .header("Authorization", bearer(user.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void EMAIL_오답과_가입수단_불일치를_구분한다() throws Exception {
        SeededUser user = seedEmailUser(
                "reauth-email@example.com",
                "이메일재인증",
                "run4life1");

        mockMvc.perform(post("/api/me/reauth")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "provider", "EMAIL",
                                "password", "wrongPass1"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTH_FAILED"));

        mockMvc.perform(post("/api/me/reauth")
                        .header("Authorization", bearer(user.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "provider", "KAKAO",
                                "kakaoAccessToken", "fresh-token"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REAUTH_PROVIDER_MISMATCH"));
    }

    @Test
    void KAKAO는_방금_검증한_회원번호가_계정과_같아야_한다() throws Exception {
        SeededUser user = seedKakaoUser("kakao-700", "카카오재인증");
        given(kakaoUserInfoProvider.retrieve("matching-token"))
                .willReturn(new KakaoUserProfile("kakao-700", null, null));
        given(kakaoUserInfoProvider.retrieve("other-token"))
                .willReturn(new KakaoUserProfile("kakao-701", null, null));
        given(kakaoUserInfoProvider.retrieve("invalid-token"))
                .willThrow(new KakaoUserInfoException(Reason.INVALID_TOKEN));

        JsonNode success = reauth(
                user.accessToken(),
                Map.of("provider", "KAKAO", "kakaoAccessToken", "matching-token"),
                200);
        assertThat(success.path("expiresInSec").asLong()).isEqualTo(300);

        assertReauthProblem(
                user.accessToken(),
                Map.of("provider", "KAKAO", "kakaoAccessToken", "other-token"),
                401,
                "REAUTH_FAILED");
        assertReauthProblem(
                user.accessToken(),
                Map.of("provider", "KAKAO", "kakaoAccessToken", "invalid-token"),
                401,
                "REAUTH_FAILED");
        assertReauthProblem(
                user.accessToken(),
                Map.of("provider", "EMAIL", "password", "run4life1"),
                409,
                "REAUTH_PROVIDER_MISMATCH");
    }

    @Test
    void 탈퇴는_현재_사용자의_유효한_DELETE_ACCOUNT_토큰만_허용한다() throws Exception {
        SeededUser first = seedEmailUser(
                "first-delete@example.com",
                "첫러너",
                "run4life1");
        SeededUser second = seedEmailUser(
                "second-delete@example.com",
                "둘러너",
                "run4life1");
        String firstReauth = reauth(
                        first.accessToken(),
                        Map.of("provider", "EMAIL", "password", "run4life1"),
                        200)
                .path("reauthToken")
                .asText();
        String expiredReauth = reauthTokenManager.issue(
                        first.userId(),
                        Instant.now().minus(301, ChronoUnit.SECONDS))
                .token();

        assertDeleteProblem(second.accessToken(), firstReauth, "INVALID_REAUTH_TOKEN");
        assertDeleteProblem(first.accessToken(), expiredReauth, "INVALID_REAUTH_TOKEN");
        assertDeleteProblem(first.accessToken(), first.accessToken(), "INVALID_REAUTH_TOKEN");
        assertDeleteProblem(first.accessToken(), null, "INVALID_REAUTH_TOKEN");

        assertThat(count("app_user")).isEqualTo(2);
    }

    private JsonNode reauth(
            String accessToken,
            Map<String, ?> request,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/me/reauth")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertReauthProblem(
            String accessToken,
            Map<String, ?> request,
            int expectedStatus,
            String expectedCode) throws Exception {
        mockMvc.perform(post("/api/me/reauth")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private void assertDeleteProblem(
            String accessToken,
            String reauthToken,
            String expectedCode) throws Exception {
        var request = delete("/api/me")
                .header("Authorization", bearer(accessToken));
        if (reauthToken != null) {
            request.header("X-Reauth-Token", reauthToken);
        }
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private SeededUser seedEmailUser(String email, String nickname, String password) {
        long userId = insertUser(nickname);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, password_hash,
                    email_verified_at, created_at, last_login_at)
                VALUES (?, 'EMAIL', ?, ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                email,
                passwordHasher.hash(password));
        return issueSession(userId, email);
    }

    private SeededUser seedKakaoUser(String subject, String nickname) {
        long userId = insertUser(nickname);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, created_at, last_login_at)
                VALUES (?, 'KAKAO', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                subject);
        return issueSession(userId, null);
    }

    private long insertUser(String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                nickname,
                nickname);
    }

    private SeededUser issueSession(long userId, String email) {
        Instant now = Instant.now().minusSeconds(1);
        IssuedTokenPair tokens = tokenIssuer.issue(userId, UUID.randomUUID(), now);
        jdbcTemplate.update(
                """
                INSERT INTO refresh_token(
                    user_id, family_id, token_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                userId,
                tokens.familyId(),
                refreshTokenHasher.hash(tokens.refreshToken()),
                Timestamp.from(tokens.refreshExpiresAt()),
                Timestamp.from(now));
        return new SeededUser(userId, email, tokens.accessToken(), tokens.refreshToken());
    }

    private long seedContest() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest(
                    canonical_key, name, region, place, contest_date, category,
                    active, checked_at, updated_at)
                VALUES ('delete-contest', '탈퇴 테스트 대회', '서울', '광장',
                    CURRENT_DATE + 10, 'ROAD', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class);
    }

    private void seedDependentData(long userId, long contestId, String email) {
        jdbcTemplate.update(
                """
                INSERT INTO user_agreement(user_id, agreement_type, version, agreed, changed_at)
                VALUES (?, 'TOS', '1.0', TRUE, CURRENT_TIMESTAMP)
                """,
                userId);
        jdbcTemplate.update(
                "INSERT INTO favorite(user_id, contest_id, created_at) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP)",
                userId,
                contestId);
        jdbcTemplate.update(
                """
                INSERT INTO itinerary(
                    user_id, contest_id, title, event, themes, start_date, end_date,
                    region_snapshot, created_at, updated_at)
                VALUES (?, ?, '삭제 동선', 'K10', '[]'::jsonb, CURRENT_DATE,
                    CURRENT_DATE + 1, '서울', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                contestId);
        jdbcTemplate.update(
                """
                INSERT INTO saved_course(
                    user_id, route_fingerprint, data_source, source_course_id,
                    course_name, region, distance_km, duration_min, difficulty, gain_m,
                    entry_lat, entry_lng, path_polyline, saved_at)
                VALUES (?, ?, 'API_GPX', 'course-delete', '삭제 코스', '서울', 5.0,
                    30, 'EASY', 10, 37.5, 127.0, '_p~iF~ps|U', CURRENT_TIMESTAMP)
                """,
                userId,
                "v1:" + "c".repeat(64));
        jdbcTemplate.update(
                """
                INSERT INTO email_verification(
                    email, purpose, token_hash, attempts, sent_at, expires_at)
                VALUES (?, 'PASSWORD_RESET', ?, 0, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '30 minutes')
                """,
                email,
                "d".repeat(64));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record SeededUser(
            long userId,
            String email,
            String accessToken,
            String refreshToken) {}
}
