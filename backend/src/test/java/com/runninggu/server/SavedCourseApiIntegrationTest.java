package com.runninggu.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.runninggu.server.auth.application.TokenIssuer;
import com.runninggu.server.savedcourse.application.SaveSavedCourseCommand;
import com.runninggu.server.savedcourse.application.SavedCourseService;
import com.runninggu.server.savedcourse.application.SavedCourseViews.Saved;
import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class SavedCourseApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private SavedCourseService service;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE saved_course, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 새_코스는_201이고_같은_geometry는_기존_snapshot과_id를_반환한다() throws Exception {
        long userId = insertUser("멱등러너", "idempotent");
        String token = accessToken(userId);
        String path = encode(new long[][] {
            {3_712_345, 12_712_345}, {3_712_346, 12_712_346}
        });

        ObjectNode clientRequest = (ObjectNode) objectMapper.readTree(
                osmRequest("처음 이름", path, List.of(10, 20)));
        clientRequest.put("routeFingerprint", "v1:" + "f".repeat(64));
        clientRequest.putArray("attributions").add("클라이언트 위조 출처");

        MvcResult created = mockMvc.perform(post("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientRequest.toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/me/courses/1")))
                .andExpect(jsonPath("$.created").value(true))
                .andReturn();
        long savedId = response(created).path("id").asLong();

        mockMvc.perform(post("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(curatedRequest("바뀐 이름", path, List.of(999))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(savedId))
                .andExpect(jsonPath("$.created").value(false));

        mockMvc.perform(get("/api/me/courses/{id}", savedId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseName").value("처음 이름"))
                .andExpect(jsonPath("$.dataSource").value("OSM_GENERATED"))
                .andExpect(jsonPath("$.elevationProfileM[0]").value(10))
                .andExpect(jsonPath("$.attributions[0]")
                        .value("© OpenStreetMap contributors"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM saved_course", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT route_fingerprint FROM saved_course WHERE id = ?",
                String.class,
                savedId)).isNotEqualTo("v1:" + "f".repeat(64));
    }

    @Test
    void 목록은_최신순_Pageable이며_큰_상세필드를_응답하지_않는다() throws Exception {
        long userId = insertUser("목록러너", "listcourse");
        String token = accessToken(userId);
        long firstId = save(token, osmRequest("먼저 저장", encode(new long[][] {{0, 0}}), List.of()));
        long secondId = save(token, curatedRequest(
                "나중 저장",
                encode(new long[][] {{1, 1}}),
                List.of(1, 2)));
        jdbcTemplate.update(
                "UPDATE saved_course SET saved_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC),
                firstId);
        jdbcTemplate.update(
                "UPDATE saved_course SET saved_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.parse("2026-08-21T00:00:00Z"), ZoneOffset.UTC),
                secondId);

        mockMvc.perform(get("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(secondId))
                .andExpect(jsonPath("$.content[0].courseName").value("나중 저장"))
                .andExpect(jsonPath("$.content[0].pathPolyline").doesNotExist())
                .andExpect(jsonPath("$.content[0].elevationProfileM").doesNotExist())
                .andExpect(jsonPath("$.content[0].attributions").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.hasNext").value(true));

        assertProblem(get("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .param("size", "51"),
                400,
                "VALIDATION_FAILED");
    }

    @Test
    void 상세와_삭제는_소유권을_검증하고_없는_id는_404다() throws Exception {
        long ownerId = insertUser("소유러너", "owner");
        long otherId = insertUser("다른러너", "other");
        String ownerToken = accessToken(ownerId);
        String otherToken = accessToken(otherId);
        long savedId = save(ownerToken, curatedRequest(
                "두루누비 코스",
                encode(new long[][] {{3_500_000, 12_900_000}}),
                List.of(2, 3)));

        mockMvc.perform(get("/api/me/courses/{id}", savedId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributions[0]")
                        .value("두루누비 걷기길(한국관광공사)"));
        assertProblem(get("/api/me/courses/{id}", savedId)
                        .header("Authorization", bearer(otherToken)),
                403,
                "FORBIDDEN");
        assertProblem(delete("/api/me/courses/{id}", savedId)
                        .header("Authorization", bearer(otherToken)),
                403,
                "FORBIDDEN");
        assertProblem(get("/api/me/courses/{id}", 999_999L)
                        .header("Authorization", bearer(ownerToken)),
                404,
                "SAVED_COURSE_NOT_FOUND");

        mockMvc.perform(delete("/api/me/courses/{id}", savedId)
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());
        assertProblem(get("/api/me/courses/{id}", savedId)
                        .header("Authorization", bearer(ownerToken)),
                404,
                "SAVED_COURSE_NOT_FOUND");
    }

    @Test
    void 잘못된_E5와_101개_고도_원천필드_불일치를_거부한다() throws Exception {
        long userId = insertUser("검증러너", "validate");
        String token = accessToken(userId);

        assertProblem(post("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(osmRequest("잘린 경로", "_", List.of())),
                400,
                "VALIDATION_FAILED");
        assertProblem(post("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(osmRequest("긴 고도", "??", java.util.Collections.nCopies(101, 0))),
                400,
                "VALIDATION_FAILED");

        String invalidSource = osmRequest("잘못된 원천", "??", List.of())
                .replace("\"dataSource\":\"OSM_GENERATED\"", "\"dataSource\":\"API_GPX\"");
        assertProblem(post("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidSource),
                400,
                "VALIDATION_FAILED");
    }

    @Test
    void 동시_중복저장도_한행과_같은_id만_반환한다() throws Exception {
        long userId = insertUser("동시러너", "concurrent");
        SaveSavedCourseCommand command = new SaveSavedCourseCommand(
                null,
                CourseDataSource.OSM_GENERATED,
                "동시 코스",
                null,
                new BigDecimal("5.000"),
                45,
                CourseDifficulty.EASY,
                10,
                List.of(),
                new BigDecimal("37.1234567"),
                new BigDecimal("127.1234567"),
                encode(new long[][] {{3_712_345, 12_712_345}, {3_712_346, 12_712_346}}));
        int taskCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Saved>> tasks = new ArrayList<>();
        for (int index = 0; index < taskCount; index++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return service.save(userId, command);
            });
        }
        try {
            List<Future<Saved>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            List<Saved> results = new ArrayList<>();
            for (Future<Saved> future : futures) {
                results.add(future.get());
            }

            assertThat(results).extracting(Saved::id).containsOnly(results.getFirst().id());
            assertThat(results).filteredOn(Saved::created).hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM saved_course", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 저장_코스_API는_모두_인증이_필요하다() throws Exception {
        mockMvc.perform(get("/api/me/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/me/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(osmRequest("미인증", "??", List.of())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/me/courses/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/me/courses/1"))
                .andExpect(status().isUnauthorized());
    }

    private long save(String token, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/me/courses")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return response(result).path("id").asLong();
    }

    private JsonNode response(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String osmRequest(String name, String path, List<Integer> elevation) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "dataSource", "OSM_GENERATED",
                "courseName", name,
                "distanceKm", 5.0,
                "durationMin", 45,
                "difficulty", "EASY",
                "gainM", 10,
                "elevationProfileM", elevation,
                "entryLat", 37.1234567,
                "entryLng", 127.1234567,
                "pathPolyline", path));
    }

    private String curatedRequest(String name, String path, List<Integer> elevation)
            throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("sourceCourseId", "T_CRS_MNG0000005117"),
                java.util.Map.entry("dataSource", "API_GPX"),
                java.util.Map.entry("courseName", name),
                java.util.Map.entry("region", "부산"),
                java.util.Map.entry("distanceKm", 17.8),
                java.util.Map.entry("durationMin", 162),
                java.util.Map.entry("difficulty", "NORMAL"),
                java.util.Map.entry("gainM", 312),
                java.util.Map.entry("elevationProfileM", elevation),
                java.util.Map.entry("entryLat", 35.11454),
                java.util.Map.entry("entryLng", 129.04076),
                java.util.Map.entry("pathPolyline", path)));
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

    private String accessToken(long userId) {
        return tokenIssuer.issue(userId, UUID.randomUUID(), Instant.now()).accessToken();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String encode(long[][] points) {
        StringBuilder encoded = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;
        for (long[] point : points) {
            appendDelta(encoded, point[0] - previousLat);
            appendDelta(encoded, point[1] - previousLng);
            previousLat = point[0];
            previousLng = point[1];
        }
        return encoded.toString();
    }

    private void appendDelta(StringBuilder encoded, long delta) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            encoded.append((char) (((value & 0x1f) | 0x20) + 63));
            value >>= 5;
        }
        encoded.append((char) (value + 63));
    }
}
