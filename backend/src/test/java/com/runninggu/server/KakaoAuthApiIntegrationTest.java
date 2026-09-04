package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runninggu.server.auth.application.KakaoUserInfoException;
import com.runninggu.server.auth.application.KakaoUserInfoException.Reason;
import com.runninggu.server.auth.application.KakaoUserInfoProvider;
import com.runninggu.server.auth.application.KakaoUserProfile;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class KakaoAuthApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KakaoUserInfoProvider userInfoProvider;

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        reset(userInfoProvider);
    }

    @Test
    void 미가입_판별은_nullable_프로필을_반환하고_DB를_변경하지_않는다() throws Exception {
        given(userInfoProvider.retrieve("new-token"))
                .willReturn(new KakaoUserProfile("100", null, null));

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kakaoAccessToken", "new-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(true))
                .andExpect(jsonPath("$.kakaoProfile.nickname").value(nullValue()))
                .andExpect(jsonPath("$.kakaoProfile.email").value(nullValue()))
                .andExpect(jsonPath("$.accessToken").doesNotExist());

        assertThat(count("app_user")).isZero();
        assertThat(count("login_identity")).isZero();
        assertThat(count("refresh_token")).isZero();
    }

    @Test
    void 카카오_가입은_계정_약관_해시세션을_원자생성하고_이후_로그인한다() throws Exception {
        KakaoUserProfile profile =
                new KakaoUserProfile("200", "카카오기본", "runner@kakao.com");
        given(userInfoProvider.retrieve("signup-token")).willReturn(profile);
        given(userInfoProvider.retrieve("login-token")).willReturn(profile);

        JsonNode signup = signup(
                "signup-token",
                "  선택닉네임  ",
                true,
                true,
                true,
                false,
                201);

        assertThat(signup.path("user").path("email").asText()).isEqualTo("runner@kakao.com");
        assertThat(signup.path("user").path("nickname").asText()).isEqualTo("선택닉네임");
        assertThat(signup.path("user").path("loginProvider").asText()).isEqualTo("KAKAO");

        Map<String, Object> identity = jdbcTemplate.queryForMap(
                """
                SELECT provider, provider_subject, email_snapshot, password_hash,
                       email_verified_at, last_login_at
                FROM login_identity
                """);
        assertThat(identity.get("provider")).isEqualTo("KAKAO");
        assertThat(identity.get("provider_subject")).isEqualTo("200");
        assertThat(identity.get("email_snapshot")).isEqualTo("runner@kakao.com");
        assertThat(identity.get("password_hash")).isNull();
        assertThat(identity.get("email_verified_at")).isNull();
        assertThat(identity.get("last_login_at")).isNotNull();

        List<Map<String, Object>> agreements = jdbcTemplate.queryForList(
                "SELECT agreement_type, version, agreed FROM user_agreement");
        assertThat(agreements).hasSize(3);
        assertThat(agreements).allSatisfy(row ->
                assertThat(row.get("version")).isEqualTo("1.0"));
        assertThat(agreements).anySatisfy(row -> {
            assertThat(row.get("agreement_type")).isEqualTo("MARKETING");
            assertThat(row.get("agreed")).isEqualTo(false);
        });

        String rawRefresh = signup.path("refreshToken").asText();
        String tokenHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_token",
                String.class);
        assertThat(tokenHash).hasSize(64).isNotEqualTo(rawRefresh);

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kakaoAccessToken", "login-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").doesNotExist())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.loginProvider").value("KAKAO"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT family_id) FROM refresh_token",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void 같은_이메일의_EMAIL_계정과_자동병합하지_않는다() throws Exception {
        seedEmailUser("same@example.com", "이메일러너");
        given(userInfoProvider.retrieve("same-email-token"))
                .willReturn(new KakaoUserProfile("300", "카카오러너", "same@example.com"));

        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kakaoAccessToken", "same-email-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(true));

        signup("same-email-token", "카카오러너", true, true, true, false, 201);
        assertThat(count("app_user")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT provider) FROM login_identity",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void 필수약관과_닉네임_중복을_계약오류로_거부한다() throws Exception {
        given(userInfoProvider.retrieve("policy-token"))
                .willReturn(new KakaoUserProfile("400", null, null));

        signup("policy-token", "정책러너", true, false, true, false, 400);
        seedEmailUser("nickname@example.com", "중복닉네임");
        JsonNode duplicate = signup(
                "policy-token", "중복닉네임", true, true, true, false, 409);
        assertThat(duplicate.path("code").asText()).isEqualTo("NICKNAME_DUPLICATED");
    }

    @Test
    void 같은_카카오_회원번호의_동시가입은_하나만_생성한다() throws Exception {
        CountDownLatch retrieved = new CountDownLatch(2);
        given(userInfoProvider.retrieve("race-token")).willAnswer(invocation -> {
            retrieved.countDown();
            if (!retrieved.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 토큰 검증 대기 시간 초과");
            }
            return new KakaoUserProfile("500", null, null);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> signupRequest("race-token", "동시러너A"));
            Future<MvcResult> second = executor.submit(() -> signupRequest("race-token", "동시러너B"));
            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(201, 409);
            MvcResult conflict = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(objectMapper.readTree(conflict.getResponse().getContentAsByteArray())
                            .path("code")
                            .asText())
                    .isEqualTo("KAKAO_ACCOUNT_DUPLICATED");
            assertThat(count("app_user")).isOne();
            assertThat(count("login_identity")).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 무효토큰과_카카오_장애를_401_502_504로_구분한다() throws Exception {
        given(userInfoProvider.retrieve("invalid"))
                .willThrow(new KakaoUserInfoException(Reason.INVALID_TOKEN));
        given(userInfoProvider.retrieve("error"))
                .willThrow(new KakaoUserInfoException(Reason.ERROR));
        given(userInfoProvider.retrieve("timeout"))
                .willThrow(new KakaoUserInfoException(Reason.TIMEOUT));

        assertProblem("invalid", 401, "INVALID_KAKAO_TOKEN");
        assertProblem("error", 502, "EXTERNAL_API_ERROR");
        assertProblem("timeout", 504, "EXTERNAL_API_TIMEOUT");
    }

    @Test
    void 빈_요청값은_외부호출전_VALIDATION_FAILED다() throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/auth/kakao/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 카카오_가입_연령확인_누락과_자료형오류는_VALIDATION_FAILED다() throws Exception {
        mockMvc.perform(post("/api/auth/kakao/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "kakaoAccessToken", "missing-age-token",
                                "nickname", "연령누락",
                                "agreements", Map.of(
                                        "tos", true,
                                        "privacy", true,
                                        "marketing", false)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode wrongType = signup(
                "wrong-age-type-token", "연령자료형", "true", true, true, false, 400);
        assertThat(wrongType.path("code").asText()).isEqualTo("VALIDATION_FAILED");

        verifyNoInteractions(userInfoProvider);
    }

    @Test
    void 카카오_가입_연령확인이_false면_외부호출과_DB생성을_하지_않는다() throws Exception {
        JsonNode response = signup(
                "underage-token", "연령미달", false, true, true, false, 400);

        assertThat(response.path("code").asText()).isEqualTo("AGE_REQUIREMENT_NOT_MET");
        assertThat(response.path("detail").asText())
                .isEqualTo("만 14세 이상만 가입할 수 있습니다.");
        verifyNoInteractions(userInfoProvider);
        assertThat(count("app_user")).isZero();
        assertThat(count("login_identity")).isZero();
        assertThat(count("user_agreement")).isZero();
        assertThat(count("refresh_token")).isZero();
    }

    private JsonNode signup(
            String token,
            String nickname,
            Object ageOver14,
            boolean tos,
            boolean privacy,
            boolean marketing,
            int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/kakao/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(
                                token,
                                nickname,
                                ageOver14,
                                tos,
                                privacy,
                                marketing)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private MvcResult signupRequest(String token, String nickname) throws Exception {
        return mockMvc.perform(post("/api/auth/kakao/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(token, nickname, true, true, true, false)))
                .andReturn();
    }

    private void assertProblem(String token, int status, String code) throws Exception {
        mockMvc.perform(post("/api/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("kakaoAccessToken", token))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is(status))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String signupJson(
            String token,
            String nickname,
            Object ageOver14,
            boolean tos,
            boolean privacy,
            boolean marketing) throws Exception {
        return json(Map.of(
                "kakaoAccessToken", token,
                "nickname", nickname,
                "ageOver14", ageOver14,
                "agreements", Map.of(
                        "tos", tos,
                        "privacy", privacy,
                        "marketing", marketing)));
    }

    private void seedEmailUser(String email, String nickname) {
        long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, lower(?), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                nickname,
                nickname);
        jdbcTemplate.update(
                """
                INSERT INTO login_identity(
                    user_id, provider, provider_subject, password_hash,
                    email_verified_at, created_at, last_login_at)
                VALUES (?, 'EMAIL', ?, '$2a$10$test-password-hash',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                email);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
