package com.runninggu.server;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
class ContestSummaryApiIntegrationTest extends PostgreSqlContainerSupport {

    private static final Instant CHECKED_AT = Instant.parse("2026-06-14T01:00:00Z");
    private static final OffsetDateTime CHECKED_AT_DB =
            OffsetDateTime.ofInstant(CHECKED_AT, ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void 월간_일별_건수를_날짜순으로_묶고_비활성_대회는_제외한다() throws Exception {
        YearMonth target = YearMonth.from(today).plusMonths(1);
        LocalDate firstDate = target.atDay(10);
        LocalDate secondDate = target.atDay(12);

        insertContest("daily-1", "서울 첫 대회", "서울", firstDate, "OPEN",
                today.minusDays(1), today.plusDays(10), true);
        insertContest("daily-2", "서울 두 번째 대회", "서울", firstDate, "BEFORE",
                today.plusDays(1), today.plusDays(10), true);
        insertContest("daily-3", "부산 대회", "부산", secondDate, "OPEN",
                today.minusDays(1), today.plusDays(10), true);
        insertContest("daily-inactive", "비활성 대회", "서울", firstDate, "OPEN",
                today.minusDays(1), today.plusDays(10), false);

        mockMvc.perform(get("/api/contests/daily-counts")
                        .param("year", String.valueOf(target.getYear()))
                        .param("month", String.valueOf(target.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.counts", hasSize(2)))
                .andExpect(jsonPath("$.counts[0].date").value(firstDate.toString()))
                .andExpect(jsonPath("$.counts[0].count").value(2))
                .andExpect(jsonPath("$.counts[1].date").value(secondDate.toString()))
                .andExpect(jsonPath("$.counts[1].count").value(1));
    }

    @Test
    void 월간_집계는_목록과_같은_검색_종목_접수중_지역_filter를_적용한다() throws Exception {
        YearMonth target = YearMonth.from(today).plusMonths(1);
        LocalDate contestDate = target.atDay(15);

        Long dateOpenId = insertContest("filter-date-open", "서울 필터런", "서울", contestDate,
                "CLOSED", today.minusDays(1), today.plusDays(10), true);
        insertEvent(dateOpenId, "K10");

        Long fallbackOpenId = insertContest("filter-fallback-open", "서울 원본런", "서울", contestDate,
                "OPEN", null, today.plusDays(20), true);
        insertEvent(fallbackOpenId, "K10");

        Long beforeId = insertContest("filter-before", "서울 접수전런", "서울", contestDate,
                "OPEN", today.plusDays(1), today.plusDays(20), true);
        insertEvent(beforeId, "K10");

        Long wrongEventId = insertContest("filter-event", "서울 오킬로런", "서울", contestDate,
                "OPEN", today.minusDays(1), today.plusDays(20), true);
        insertEvent(wrongEventId, "K5");

        Long wrongRegionId = insertContest("filter-region", "서울 이름 부산런", "부산", contestDate,
                "OPEN", today.minusDays(1), today.plusDays(20), true);
        insertEvent(wrongRegionId, "K10");

        mockMvc.perform(get("/api/contests/daily-counts")
                        .param("year", String.valueOf(target.getYear()))
                        .param("month", String.valueOf(target.getMonthValue()))
                        .param("q", "서울")
                        .param("events", "K10")
                        .param("openOnly", "true")
                        .param("regions", "서울"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts", hasSize(1)))
                .andExpect(jsonPath("$.counts[0].date").value(contestDate.toString()))
                .andExpect(jsonPath("$.counts[0].count").value(2));
    }

    @Test
    void 월간_집계의_잘못된_연월_enum과_누락_parameter는_VALIDATION_FAILED다() throws Exception {
        assertValidationFailed(get("/api/contests/daily-counts")
                .param("year", "2026")
                .param("month", "13"), "/api/contests/daily-counts");
        assertValidationFailed(get("/api/contests/daily-counts")
                .param("year", "2026")
                .param("month", "8")
                .param("events", "ULTRA"), "/api/contests/daily-counts");
        assertValidationFailed(get("/api/contests/daily-counts")
                .param("month", "8"), "/api/contests/daily-counts");
    }

    @Test
    void 마감_임박은_OPEN_예정대회를_마감일순으로_반환하고_제외조건을_지킨다() throws Exception {
        Long fallbackOpenId = insertContest("closing-fallback", "서울 원본상태런", "서울",
                today.plusDays(20), "OPEN", null, today.plusDays(1), true);
        insertEvent(fallbackOpenId, "K10");
        insertSource(fallbackOpenId, "MARATHON_ONLINE", "closing-fallback-source", true);

        Long dateOpenId = insertContest("closing-date", "부산 날짜런", "부산",
                today.plusDays(10), "CLOSED", today.minusDays(1), today.plusDays(2), true);
        insertEvent(dateOpenId, "HALF");
        insertSource(dateOpenId, "MARATHON_GO", "closing-date-source", true);

        insertContest("closing-before", "접수 전", "서울", today.plusDays(10), "OPEN",
                today.plusDays(1), today.plusDays(3), true);
        insertContest("closing-closed", "마감 완료", "서울", today.plusDays(10), "OPEN",
                null, today.minusDays(1), true);
        insertContest("closing-inactive", "비활성", "서울", today.plusDays(10), "OPEN",
                null, today.plusDays(1), false);
        insertContest("closing-past", "지난 대회", "서울", today.minusDays(1), "OPEN",
                null, today.plusDays(1), true);
        insertContest("closing-no-end", "마감일 없음", "서울", today.plusDays(10), "OPEN",
                today.minusDays(1), null, true);

        mockMvc.perform(get("/api/contests/closing-soon"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(fallbackOpenId))
                .andExpect(jsonPath("$.items[0].name").value("서울 원본상태런"))
                .andExpect(jsonPath("$.items[0].events", contains("K10")))
                .andExpect(jsonPath("$.items[0].sources", contains("MARATHON_ONLINE")))
                .andExpect(jsonPath("$.items[0].regStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[0].favorite").value(false))
                .andExpect(jsonPath("$.items[0].dDayApply").value(1))
                .andExpect(jsonPath("$.items[1].id").value(dateOpenId))
                .andExpect(jsonPath("$.items[1].dDayApply").value(2));

        mockMvc.perform(get("/api/contests/closing-soon").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(fallbackOpenId));
    }

    @Test
    void 마감_임박_limit는_1부터_4까지만_허용한다() throws Exception {
        assertValidationFailed(
                get("/api/contests/closing-soon").param("limit", "0"),
                "/api/contests/closing-soon");
        assertValidationFailed(
                get("/api/contests/closing-soon").param("limit", "5"),
                "/api/contests/closing-soon");
    }

    @Test
    void 상세는_카드와_좌표_주최자_Dday를_계약대로_반환한다() throws Exception {
        Long contestId = insertContest(
                "detail-active",
                "세종 상세런",
                "세종",
                "세종중앙공원",
                today.plusDays(11),
                LocalTime.of(8, 0),
                "CLOSED",
                today.minusDays(1),
                today.plusDays(5),
                "세종시",
                "https://example.com/official",
                BigDecimal.valueOf(36.48),
                BigDecimal.valueOf(127.28),
                "https://example.com/image.webp",
                true);
        insertEvent(contestId, "FULL");
        insertEvent(contestId, "K10");
        insertSource(contestId, "MARATHON_GO", "detail-go", true);

        mockMvc.perform(get("/api/contests/{id}", contestId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(contestId))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.name").value("세종 상세런"))
                .andExpect(jsonPath("$.region").value("세종"))
                .andExpect(jsonPath("$.place").value("세종중앙공원"))
                .andExpect(jsonPath("$.contestDate").value(today.plusDays(11).toString()))
                .andExpect(jsonPath("$.startTime").value("08:00"))
                .andExpect(jsonPath("$.events", contains("FULL", "K10")))
                .andExpect(jsonPath("$.regStatus").value("OPEN"))
                .andExpect(jsonPath("$.organizer").value("세종시"))
                .andExpect(jsonPath("$.officialUrl").value("https://example.com/official"))
                .andExpect(jsonPath("$.lat").value(36.48))
                .andExpect(jsonPath("$.lng").value(127.28))
                .andExpect(jsonPath("$.sources", contains("MARATHON_GO")))
                .andExpect(jsonPath("$.checkedAt").value(CHECKED_AT.toString()))
                .andExpect(jsonPath("$.favorite").value(false))
                .andExpect(jsonPath("$.dDay").value(11));
    }

    @Test
    void 비활성_과거_대회도_상세는_유지하고_nullable_필드를_반환한다() throws Exception {
        Long contestId = insertContest(
                "detail-inactive",
                "정보 제공 종료 대회",
                "서울",
                "서울광장",
                today.minusDays(3),
                null,
                "CLOSED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
        insertSource(contestId, "MARATHON_ONLINE", "detail-inactive-source", false);

        mockMvc.perform(get("/api/contests/{id}", contestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.dDay").value(-3))
                .andExpect(jsonPath("$.startTime").isEmpty())
                .andExpect(jsonPath("$.organizer").isEmpty())
                .andExpect(jsonPath("$.officialUrl").isEmpty())
                .andExpect(jsonPath("$.lat").isEmpty())
                .andExpect(jsonPath("$.lng").isEmpty())
                .andExpect(jsonPath("$.events", hasSize(0)))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    void 없는_대회_상세는_CONTEST_NOT_FOUND다() throws Exception {
        mockMvc.perform(get("/api/contests/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONTEST_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/contests/999999"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    private void assertValidationFailed(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String instance) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value(instance))
                .andExpect(jsonPath("$.traceId").isString());
    }

    private Long insertContest(
            String canonicalKey,
            String name,
            String region,
            LocalDate contestDate,
            String sourceStatus,
            LocalDate applyStart,
            LocalDate applyEnd,
            boolean active) {
        return insertContest(
                canonicalKey,
                name,
                region,
                region + " 경기장",
                contestDate,
                LocalTime.of(9, 0),
                sourceStatus,
                applyStart,
                applyEnd,
                null,
                null,
                null,
                null,
                null,
                active);
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
            String organizer,
            String officialUrl,
            BigDecimal lat,
            BigDecimal lng,
            String imageUrl,
            boolean active) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest (
                    canonical_key, name, region, place, lat, lng, contest_date, start_time,
                    source_status, apply_start, apply_end, organizer, official_url, image_url,
                    category, active, checked_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ROAD', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                canonicalKey,
                name,
                region,
                place,
                lat,
                lng,
                contestDate,
                startTime,
                sourceStatus,
                applyStart,
                applyEnd,
                organizer,
                officialUrl,
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
}
