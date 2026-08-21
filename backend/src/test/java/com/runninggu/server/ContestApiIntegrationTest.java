package com.runninggu.server;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class ContestApiIntegrationTest extends PostgreSqlContainerSupport {

    private static final Instant CHECKED_AT = Instant.parse("2026-06-14T01:00:00Z");
    private static final OffsetDateTime CHECKED_AT_DB =
            OffsetDateTime.ofInstant(CHECKED_AT, ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock businessClock;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE contest_snapshot_import, contest_event, contest_source, contest RESTART IDENTITY CASCADE");
        today = LocalDate.now(businessClock);
    }

    @Test
    void 게스트에게_활성_예정대회를_계약_카드로_정렬해_반환한다() throws Exception {
        TestData data = insertTestData();

        mockMvc.perform(get("/api/contests"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[0].id").value(data.openContestId()))
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.items[0].name").value("서울 오픈런"))
                .andExpect(jsonPath("$.items[0].region").value("서울"))
                .andExpect(jsonPath("$.items[0].place").value("여의도 한강공원"))
                .andExpect(jsonPath("$.items[0].contestDate").value(today.toString()))
                .andExpect(jsonPath("$.items[0].startTime").value("08:00"))
                .andExpect(jsonPath("$.items[0].events", contains("FULL", "K10")))
                .andExpect(jsonPath("$.items[0].regStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[0].applyStart").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.items[0].applyEnd").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.items[0].imageUrl").value("https://example.com/seoul.webp"))
                .andExpect(jsonPath(
                        "$.items[0].sources",
                        contains("MARATHON_GO", "MARATHON_ONLINE")))
                .andExpect(jsonPath("$.items[0].checkedAt").value(CHECKED_AT.toString()))
                .andExpect(jsonPath("$.items[0].favorite").value(false))
                .andExpect(jsonPath("$.items[1].id").value(data.beforeContestId()))
                .andExpect(jsonPath("$.items[1].startTime").value(nullValue()))
                .andExpect(jsonPath("$.items[1].regStatus").value("BEFORE"))
                .andExpect(jsonPath("$.items[2].id").value(data.fallbackOpenContestId()))
                .andExpect(jsonPath("$.items[2].regStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[2].sources", contains("MARATHON_GO")))
                .andExpect(jsonPath("$.items[3].id").value(data.closedContestId()))
                .andExpect(jsonPath("$.items[3].regStatus").value("CLOSED"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 불투명_cursor로_중복없이_다음_페이지를_조회한다() throws Exception {
        TestData data = insertTestData();

        MvcResult firstPage = mockMvc.perform(get("/api/contests").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(data.openContestId()))
                .andExpect(jsonPath("$.items[1].id").value(data.beforeContestId()))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(firstPage.getResponse().getContentAsByteArray());
        String cursor = firstBody.path("nextCursor").asText();

        mockMvc.perform(get("/api/contests")
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(data.fallbackOpenContestId()))
                .andExpect(jsonPath("$.items[1].id").value(data.closedContestId()))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 검색_종목_접수중_지역_날짜_필터를_AND로_적용한다() throws Exception {
        TestData data = insertTestData();

        mockMvc.perform(get("/api/contests")
                        .param("q", "한강")
                        .param("events", "K10")
                        .param("openOnly", "true")
                        .param("regions", "서울")
                        .param("date", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(data.openContestId()));
    }

    @Test
    void openOnly는_날짜_파생과_원본상태_fallback을_같이_적용한다() throws Exception {
        TestData data = insertTestData();

        mockMvc.perform(get("/api/contests").param("openOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(data.openContestId()))
                .andExpect(jsonPath("$.items[1].id").value(data.fallbackOpenContestId()));
    }

    @Test
    void 복수_종목_filter는_OR로_적용한다() throws Exception {
        TestData data = insertTestData();

        mockMvc.perform(get("/api/contests").param("events", "HALF", "K5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(data.beforeContestId()))
                .andExpect(jsonPath("$.items[1].id").value(data.fallbackOpenContestId()));
    }

    @Test
    void 정상_0건은_빈_페이지를_반환한다() throws Exception {
        insertTestData();

        mockMvc.perform(get("/api/contests").param("q", "존재하지 않는 대회"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 잘못된_cursor_size_enum_date는_VALIDATION_FAILED다() throws Exception {
        assertValidationFailed("cursor", "%%%invalid%%%");
        assertValidationFailed("size", "0");
        assertValidationFailed("size", "51");
        assertValidationFailed("events", "TEN_K");
        assertValidationFailed("date", "2026-99-99");
    }

    private void assertValidationFailed(String name, String value) throws Exception {
        mockMvc.perform(get("/api/contests").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.instance").value("/api/contests"));
    }

    private TestData insertTestData() {
        Long openContestId = insertContest(
                "open-seoul",
                "서울 오픈런",
                "서울",
                "여의도 한강공원",
                today,
                LocalTime.of(8, 0),
                "CLOSED",
                today.minusDays(1),
                today.plusDays(1),
                "https://example.com/seoul.webp",
                true);
        insertEvent(openContestId, "K10");
        insertEvent(openContestId, "FULL");
        insertSource(openContestId, "MARATHON_GO", "go-open", true);
        insertSource(openContestId, "MARATHON_ONLINE", "online-open", true);

        Long beforeContestId = insertContest(
                "before-busan",
                "부산 접수전런",
                "부산",
                "광안리 해수욕장",
                today.plusDays(1),
                null,
                "OPEN",
                today.plusDays(2),
                today.plusDays(20),
                null,
                true);
        insertEvent(beforeContestId, "HALF");
        insertSource(beforeContestId, "MARATHON_ONLINE", "online-before", true);

        Long fallbackOpenContestId = insertContest(
                "fallback-open-seoul",
                "서울 원본상태런",
                "서울",
                "서울광장",
                today.plusDays(2),
                LocalTime.of(9, 30),
                "OPEN",
                null,
                today.plusDays(30),
                null,
                true);
        insertEvent(fallbackOpenContestId, "K5");
        insertSource(fallbackOpenContestId, "MARATHON_GO", "go-fallback", true);
        insertSource(fallbackOpenContestId, "MARATHON_ONLINE", "online-fallback", false);

        Long closedContestId = insertContest(
                "closed-jeju",
                "제주 마감런",
                "제주",
                "제주종합경기장",
                today.plusDays(3),
                LocalTime.of(7, 0),
                "OPEN",
                null,
                today.minusDays(1),
                null,
                true);
        insertEvent(closedContestId, "FULL");
        insertSource(closedContestId, "MARATHON_GO", "go-closed", true);

        Long pastContestId = insertContest(
                "past-race",
                "지난 대회",
                "서울",
                "서울광장",
                today.minusDays(1),
                LocalTime.NOON,
                "CLOSED",
                null,
                null,
                null,
                true);
        insertSource(pastContestId, "MARATHON_GO", "go-past", true);

        Long inactiveContestId = insertContest(
                "inactive-race",
                "비활성 대회",
                "서울",
                "서울광장",
                today.plusDays(4),
                LocalTime.NOON,
                "OPEN",
                null,
                null,
                null,
                false);
        insertSource(inactiveContestId, "MARATHON_GO", "go-inactive", false);

        return new TestData(
                openContestId,
                beforeContestId,
                fallbackOpenContestId,
                closedContestId);
    }

    private Long insertContest(
            String canonicalKey,
            String name,
            String region,
            String place,
            LocalDate contestDate,
            LocalTime startTime,
            String sourceStatus,
            LocalDate applyStart,
            LocalDate applyEnd,
            String imageUrl,
            boolean active) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest (
                    canonical_key, name, region, place, contest_date, start_time,
                    source_status, apply_start, apply_end, image_url, category,
                    active, checked_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ROAD', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                canonicalKey,
                name,
                region,
                place,
                contestDate,
                startTime,
                sourceStatus,
                applyStart,
                applyEnd,
                imageUrl,
                active,
                CHECKED_AT_DB,
                CHECKED_AT_DB);
    }

    private void insertEvent(Long contestId, String eventType) {
        jdbcTemplate.update(
                "INSERT INTO contest_event (contest_id, event_type) VALUES (?, ?)",
                contestId,
                eventType);
    }

    private void insertSource(
            Long contestId,
            String sourceType,
            String externalId,
            boolean active) {
        jdbcTemplate.update(
                """
                INSERT INTO contest_source (
                    contest_id, source_type, external_id, active,
                    consecutive_missing_count, fetched_at, raw_payload
                ) VALUES (?, ?, ?, ?, 0, ?, CAST(? AS jsonb))
                """,
                contestId,
                sourceType,
                externalId,
                active,
                CHECKED_AT_DB,
                "{}");
    }

    private record TestData(
            Long openContestId,
            Long beforeContestId,
            Long fallbackOpenContestId,
            Long closedContestId) {}
}
