package com.runninggu.server;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.festival.application.FestivalProvider;
import com.runninggu.server.festival.application.FestivalProviderException;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class NearbyFestivalApiIntegrationTest extends PostgreSqlContainerSupport {

    private static final LocalDate CONTEST_DATE = LocalDate.of(2026, 8, 21);
    private static final BigDecimal CONTEST_LAT = new BigDecimal("36.4912000");
    private static final BigDecimal CONTEST_LNG = new BigDecimal("127.2714000");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private FestivalProvider festivalProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE contest_snapshot_import, contest_event, contest_source, contest RESTART IDENTITY CASCADE");
        cacheManager.getCache(CacheConfig.NEARBY_FESTIVALS_CACHE).clear();
    }

    @Test
    void 공개_API가_거리순_축제를_계약대로_반환하고_성공을_캐시한다() throws Exception {
        long contestId = insertContest("festival-api", CONTEST_LAT, CONTEST_LNG);
        LocalDate requestedStart = CONTEST_DATE.minusDays(14);
        given(festivalProvider.searchStartingFrom(requestedStart)).willReturn(List.of(
                festival("far", "먼 축제", new BigDecimal("36.5500000")),
                festival("near", "가까운 축제", CONTEST_LAT)));

        MockHttpServletRequestBuilder request =
                get("/api/contests/{id}/festivals", contestId);
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].contentId").value("near"))
                .andExpect(jsonPath("$.items[0].name").value("가까운 축제"))
                .andExpect(jsonPath("$.items[0].startDate").value("2026-08-20"))
                .andExpect(jsonPath("$.items[0].endDate").value("2026-08-25"))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.0))
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].address").value(""))
                .andExpect(jsonPath("$.items[1].contentId").value("far"));

        mockMvc.perform(request).andExpect(status().isOk());

        verify(festivalProvider, times(1)).searchStartingFrom(requestedStart);
    }

    @Test
    void 정상_빈_결과는_200과_빈_items다() throws Exception {
        long contestId = insertContest("festival-empty", CONTEST_LAT, CONTEST_LNG);
        given(festivalProvider.searchStartingFrom(CONTEST_DATE.minusDays(14)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/contests/{id}/festivals", contestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 없는_대회와_좌표_없는_대회를_404와_409로_구분한다() throws Exception {
        assertProblem(
                get("/api/contests/{id}/festivals", 9999),
                404,
                "CONTEST_NOT_FOUND",
                "/api/contests/9999/festivals");

        long contestId = insertContest("festival-no-location", null, null);
        assertProblem(
                get("/api/contests/{id}/festivals", contestId),
                409,
                "CONTEST_LOCATION_UNAVAILABLE",
                "/api/contests/" + contestId + "/festivals");

        verifyNoInteractions(festivalProvider);
    }

    @Test
    void 외부오류와_타임아웃을_502와_504로_구분한다() throws Exception {
        long errorContestId = insertContest("festival-error", CONTEST_LAT, CONTEST_LNG);
        long timeoutContestId = insertContest("festival-timeout", CONTEST_LAT, CONTEST_LNG);
        LocalDate requestedStart = CONTEST_DATE.minusDays(14);
        given(festivalProvider.searchStartingFrom(requestedStart))
                .willThrow(new FestivalProviderException(Reason.ERROR))
                .willThrow(new FestivalProviderException(Reason.TIMEOUT));

        assertProblem(
                get("/api/contests/{id}/festivals", errorContestId),
                502,
                "EXTERNAL_API_ERROR",
                "/api/contests/" + errorContestId + "/festivals");
        assertProblem(
                get("/api/contests/{id}/festivals", timeoutContestId),
                504,
                "EXTERNAL_API_TIMEOUT",
                "/api/contests/" + timeoutContestId + "/festivals");
    }

    @Test
    void OpenAPI에_경로_parameter와_응답_스키마를_공개한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                                "$['paths']['/api/contests/{contestId}/festivals']['get']")
                        .exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/contests/{contestId}/festivals']['get']"
                                        + "['parameters'][?(@.name == 'contestId' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$.components.schemas.NearbyFestivalListResponse.properties.items")
                        .exists())
                .andExpect(jsonPath(
                                "$.components.schemas.NearbyFestivalResponse.properties.contentId")
                        .exists())
                .andExpect(jsonPath(
                                "$.components.schemas.NearbyFestivalResponse.properties.distanceKm")
                        .exists())
                .andExpect(jsonPath(
                                "$.components.schemas.NearbyFestivalResponse.properties.imageUrl")
                        .exists());
    }

    private Festival festival(String contentId, String name, BigDecimal lat) {
        return new Festival(
                contentId,
                name,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 25),
                lat,
                CONTEST_LNG,
                null,
                "");
    }

    private long insertContest(String canonicalKey, BigDecimal lat, BigDecimal lng) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO contest (
                    canonical_key, name, region, place, lat, lng, contest_date,
                    category, active, checked_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                canonicalKey,
                "테스트 대회",
                "세종",
                "테스트 경기장",
                lat,
                lng,
                CONTEST_DATE,
                "ROAD",
                true,
                now,
                now);
        return id;
    }

    private void assertProblem(
            MockHttpServletRequestBuilder request,
            int expectedStatus,
            String expectedCode,
            String expectedInstance) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.instance").value(expectedInstance))
                .andExpect(jsonPath("$.traceId").isString());
    }
}
