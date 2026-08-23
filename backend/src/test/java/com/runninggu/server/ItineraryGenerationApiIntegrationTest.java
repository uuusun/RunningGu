package com.runninggu.server;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.itinerary.application.ItineraryPoiPoolLoader;
import com.runninggu.server.itinerary.domain.ItineraryPlace;
import com.runninggu.server.itinerary.domain.PoiPools;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ItineraryGenerationApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ItineraryPoiPoolLoader poolLoader;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE contest_snapshot_import, contest_event, contest_source, contest RESTART IDENTITY CASCADE");
        given(poolLoader.load(anyList(), any(), any())).willReturn(pools());
    }

    @Test
    void 게스트가_지역없는_기간제목과_상대오프셋을_가진_동선을_생성한다() throws Exception {
        long contestId = insertContest(true, new BigDecimal("36.4912000"), new BigDecimal("127.2714000"));

        mockMvc.perform(post("/api/itineraries/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(contestId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.title").value("2박 3일"))
                .andExpect(jsonPath("$.contestId").value(contestId))
                .andExpect(jsonPath("$.event").value("HALF"))
                .andExpect(jsonPath("$.themes[0]").value("TOUR"))
                .andExpect(jsonPath("$.themes[1]").value("FOOD"))
                .andExpect(jsonPath("$.startDate").value("2026-08-21"))
                .andExpect(jsonPath("$.endDate").value("2026-08-23"))
                .andExpect(jsonPath("$.hotel.name").value("호텔 세종 가온"))
                .andExpect(jsonPath("$.recovery.label").value("D+1 회복 모드"))
                .andExpect(jsonPath("$.recovery.note").value("온천+짧은 산책(고강도 제외)"))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].dayIndex").value(-1))
                .andExpect(jsonPath("$.days[0].dayLabel").value("D-1"))
                .andExpect(jsonPath("$.days[0].recovery").value(false))
                .andExpect(jsonPath("$.days[1].dayIndex").value(0))
                .andExpect(jsonPath("$.days[1].dayLabel").value("D-day"))
                .andExpect(jsonPath("$.days[1].blocks[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.days[1].blocks[0].category").value("RACE"))
                .andExpect(jsonPath("$.days[1].blocks[0].placeName").value("세종호수공원"))
                .andExpect(jsonPath("$.days[1].blocks[0].address")
                        .value("세종특별자치시 다솜로 216"))
                .andExpect(jsonPath("$.days[1].blocks[0].blockType").value("RACE"))
                .andExpect(jsonPath("$.days[1].blocks[0].systemManaged").value(true))
                .andExpect(jsonPath("$.days[2].dayIndex").value(1))
                .andExpect(jsonPath("$.days[2].recovery").value(true))
                .andExpect(jsonPath("$.sources").doesNotExist());
    }

    @Test
    void 정상_빈_POI_풀은_장소만_null로_낮추고_생성은_성공한다() throws Exception {
        long contestId = insertContest(true, new BigDecimal("36.4912000"), new BigDecimal("127.2714000"));
        given(poolLoader.load(anyList(), any(), any()))
                .willReturn(new PoiPools(Map.of(), Map.of()));

        mockMvc.perform(post("/api/itineraries/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(contestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].blocks[1].placeName").value(nullValue()))
                .andExpect(jsonPath("$.days[0].blocks[1].lat").value(nullValue()))
                .andExpect(jsonPath("$.days[1].blocks[0].placeName").value("세종호수공원"));
    }

    @Test
    void 요청_필수값_날짜_종목_취향과_기간을_계약_오류로_응답한다() throws Exception {
        long contestId = insertContest(true, new BigDecimal("36.4912000"), new BigDecimal("127.2714000"));

        assertProblem("{}", 400, "VALIDATION_FAILED");
        assertProblem(requestBody(contestId).replace("2026-08-21", "2026-99-99"),
                400,
                "VALIDATION_FAILED");
        assertProblem(requestBody(contestId).replace("\"HALF\"", "\"TEN_K\""),
                400,
                "VALIDATION_FAILED");
        assertProblem(requestBody(contestId).replace(
                        "\"2026-08-21\"", "\"2026-08-24\""),
                400,
                "INVALID_TRAVEL_PERIOD");
    }

    @Test
    void 대회_없음_비활성_좌표없음을_서로_다른_code로_응답한다() throws Exception {
        assertProblem(requestBody(999), 404, "CONTEST_NOT_FOUND");

        long inactive = insertContest(false, new BigDecimal("36.4912000"), new BigDecimal("127.2714000"));
        assertProblem(requestBody(inactive), 409, "CONTEST_INACTIVE");

        jdbcTemplate.execute(
                "TRUNCATE TABLE contest_snapshot_import, contest_event, contest_source, contest RESTART IDENTITY CASCADE");
        long noLocation = insertContest(true, null, null);
        assertProblem(requestBody(noLocation), 409, "CONTEST_LOCATION_UNAVAILABLE");
    }

    @Test
    void OpenAPI에_생성_경로와_요청응답_계약을_공개한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/itineraries/generate']['post']").exists())
                .andExpect(jsonPath("$.components.schemas.GenerateItineraryRequest.properties.contestId")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.GenerateItineraryRequest.properties.event.enum")
                        .isArray())
                .andExpect(jsonPath("$.components.schemas.GenerateItineraryResponse.properties.title")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.DayResponse.properties.dayIndex")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.BlockResponse.properties.systemManaged")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.GenerateItineraryResponse.properties.sources")
                        .doesNotExist());
    }

    private void assertProblem(String body, int statusCode, String code) throws Exception {
        mockMvc.perform(post("/api/itineraries/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.instance").value("/api/itineraries/generate"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    private long insertContest(boolean active, BigDecimal lat, BigDecimal lng) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO contest (
                    canonical_key, name, region, place, road_address, lat, lng,
                    contest_date, start_time, source_status, category, active,
                    checked_at, updated_at
                ) VALUES (
                    'sejong-running-festival', '세종 러닝 페스티벌', '세종', '세종호수공원',
                    '세종특별자치시 다솜로 216', ?, ?, DATE '2026-08-22', TIME '09:00',
                    'OPEN', 'ROAD', ?, TIMESTAMPTZ '2026-08-20 00:00:00Z',
                    TIMESTAMPTZ '2026-08-20 00:00:00Z'
                ) RETURNING id
                """,
                Long.class,
                lat,
                lng,
                active);
    }

    private String requestBody(long contestId) {
        return """
                {
                  "contestId": %d,
                  "startDate": "2026-08-21",
                  "endDate": "2026-08-23",
                  "event": "HALF",
                  "themes": ["TOUR", "FOOD"],
                  "hotel": {
                    "name": "호텔 세종 가온",
                    "lat": 36.4901,
                    "lng": 127.2688
                  }
                }
                """.formatted(contestId);
    }

    private PoiPools pools() {
        Map<PoiCategory, List<ItineraryPlace>> places = new LinkedHashMap<>();
        for (PoiCategory category : List.of(
                PoiCategory.FOOD,
                PoiCategory.TOUR,
                PoiCategory.WELLNESS)) {
            places.put(category, List.of(
                    new ItineraryPlace(
                            category.name() + " 장소 1",
                            "세종특별자치시",
                            new BigDecimal("36.5000000"),
                            new BigDecimal("127.2800000"),
                            category.name() + " 설명 1"),
                    new ItineraryPlace(
                            category.name() + " 장소 2",
                            "세종특별자치시",
                            new BigDecimal("36.5100000"),
                            new BigDecimal("127.2900000"),
                            category.name() + " 설명 2"),
                    new ItineraryPlace(
                            category.name() + " 장소 3",
                            "세종특별자치시",
                            new BigDecimal("36.5200000"),
                            new BigDecimal("127.3000000"),
                            category.name() + " 설명 3")));
        }
        return new PoiPools(
                places,
                Map.of(
                        PoiCategory.FOOD, "LIVE",
                        PoiCategory.TOUR, "LIVE",
                        PoiCategory.WELLNESS, "LIVE"));
    }
}
