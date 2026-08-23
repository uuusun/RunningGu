package com.runninggu.server;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.auth.application.TokenIssuer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class FavoriteApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private Clock businessClock;

    private LocalDate today;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE favorite, itinerary, contest, app_user RESTART IDENTITY CASCADE");
        today = LocalDate.now(businessClock);
    }

    @Test
    void 찜은_멱등이고_비활성_지난대회도_최신저장순으로_조회한다() throws Exception {
        long userId = insertUser("찜러너", "fav-runner");
        long activeContestId = insertContest(
                "active-favorite", "활성 대회", today.plusDays(10), true);
        long inactivePastContestId = insertContest(
                "inactive-past-favorite", "지난 비활성 대회", today.minusDays(2), false);
        String token = accessToken(userId);

        putFavorite(token, activeContestId);
        putFavorite(token, activeContestId);
        putFavorite(token, inactivePastContestId);
        jdbcTemplate.update(
                "UPDATE favorite SET created_at = ? WHERE user_id = ? AND contest_id = ?",
                OffsetDateTime.ofInstant(
                        Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                userId,
                activeContestId);
        jdbcTemplate.update(
                "UPDATE favorite SET created_at = ? WHERE user_id = ? AND contest_id = ?",
                OffsetDateTime.ofInstant(
                        Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC),
                userId,
                inactivePastContestId);

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE user_id = ?",
                Integer.class,
                userId)).isEqualTo(2);

        mockMvc.perform(get("/api/me/favorites")
                        .header("Authorization", bearer(token))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(inactivePastContestId))
                .andExpect(jsonPath("$.content[0].name").value("지난 비활성 대회"))
                .andExpect(jsonPath("$.content[0].active").value(false))
                .andExpect(jsonPath("$.content[0].favorite").value(true))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.hasNext").value(true));

        mockMvc.perform(get("/api/me/favorites")
                        .header("Authorization", bearer(token))
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(activeContestId))
                .andExpect(jsonPath("$.page.hasNext").value(false));

        assertProblem(get("/api/me/favorites")
                        .header("Authorization", bearer(token))
                        .param("size", "51"),
                400,
                "VALIDATION_FAILED");
    }

    @Test
    void 찜_해제는_멱등이고_사용자별로_격리하며_없는_대회는_거부한다() throws Exception {
        long firstUserId = insertUser("첫러너", "first-fav");
        long secondUserId = insertUser("둘러너", "second-fav");
        long contestId = insertContest(
                "shared-favorite", "공통 대회", today.plusDays(10), true);
        String firstToken = accessToken(firstUserId);
        String secondToken = accessToken(secondUserId);

        putFavorite(firstToken, contestId);
        putFavorite(secondToken, contestId);

        mockMvc.perform(delete("/api/me/favorites/{contestId}", contestId)
                        .header("Authorization", bearer(firstToken)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/me/favorites/{contestId}", contestId)
                        .header("Authorization", bearer(firstToken)))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite WHERE contest_id = ?",
                Integer.class,
                contestId)).isEqualTo(1);
        mockMvc.perform(get("/api/me/favorites")
                        .header("Authorization", bearer(secondToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(contestId));

        assertProblem(put("/api/me/favorites/{contestId}", 999_999L)
                        .header("Authorization", bearer(firstToken)),
                404,
                "CONTEST_NOT_FOUND");
    }

    @Test
    void 로그인한_공개_대회응답만_실제_찜상태를_반환한다() throws Exception {
        long userId = insertUser("공개러너", "public-fav");
        long contestId = insertContest(
                "public-favorite", "공개 찜 대회", today.plusDays(5), true);
        String token = accessToken(userId);
        putFavorite(token, contestId);

        mockMvc.perform(get("/api/contests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].favorite").value(false));
        mockMvc.perform(get("/api/contests")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(contestId))
                .andExpect(jsonPath("$.items[0].favorite").value(true));

        mockMvc.perform(get("/api/contests/closing-soon")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(contestId))
                .andExpect(jsonPath("$.items[0].favorite").value(true));

        mockMvc.perform(get("/api/contests/{id}", contestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(false));
        mockMvc.perform(get("/api/contests/{id}", contestId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorite").value(true));
    }

    @Test
    void 마이_찜_API는_모두_인증이_필요하다() throws Exception {
        mockMvc.perform(get("/api/me/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(put("/api/me/favorites/{contestId}", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(delete("/api/me/favorites/{contestId}", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void putFavorite(String token, long contestId) throws Exception {
        mockMvc.perform(put("/api/me/favorites/{contestId}", contestId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    private void assertProblem(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int statusCode,
            String code) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    private long insertUser(String nickname, String nicknameKey) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO app_user(nickname, nickname_key, created_at, updated_at)
                VALUES (?, ?, now(), now()) RETURNING id
                """,
                Long.class,
                nickname,
                nicknameKey);
    }

    private long insertContest(
            String canonicalKey,
            String name,
            LocalDate contestDate,
            boolean active) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest(
                    canonical_key, name, region, place, contest_date, start_time,
                    source_status, apply_start, apply_end, category, active,
                    checked_at, updated_at)
                VALUES (?, ?, '서울', '서울광장', ?, TIME '09:00', 'CLOSED', ?, ?,
                    'ROAD', ?, now(), now())
                RETURNING id
                """,
                Long.class,
                canonicalKey,
                name,
                contestDate,
                today.minusDays(1),
                today.plusDays(3),
                active);
    }

    private String accessToken(long userId) {
        return tokenIssuer.issue(userId, UUID.randomUUID(), Instant.now()).accessToken();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
