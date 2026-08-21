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
import com.runninggu.server.common.config.ClockConfig;
import com.runninggu.server.festival.application.FestivalProvider;
import com.runninggu.server.festival.application.FestivalProviderException;
import com.runninggu.server.festival.application.FestivalProviderException.Reason;
import com.runninggu.server.festival.domain.Festival;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(HomeFestivalApiIntegrationTest.FixedClockConfiguration.class)
class HomeFestivalApiIntegrationTest extends PostgreSqlContainerSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);
    private static final LocalDate MONTH_START = LocalDate.of(2026, 8, 1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private FestivalProvider festivalProvider;

    @BeforeEach
    void setUp() {
        cacheManager.getCache(CacheConfig.HOME_FESTIVALS_CACHE).clear();
    }

    @Test
    void 공개_API가_월간_축제를_계약대로_반환하고_성공을_캐시한다() throws Exception {
        given(festivalProvider.searchStartingFrom(MONTH_START)).willReturn(List.of(
                festival(
                        "upcoming",
                        "다가오는 축제",
                        LocalDate.of(2026, 8, 25),
                        LocalDate.of(2026, 8, 26),
                        "부산광역시 해운대구",
                        null),
                festival(
                        "ongoing",
                        "진행 중 축제",
                        LocalDate.of(2026, 8, 20),
                        LocalDate.of(2026, 8, 22),
                        "서울특별시 종로구",
                        "https://example.test/ongoing.jpg"),
                festival(
                        "unknown",
                        "지역 미상 축제",
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 2),
                        "알수없는지역 행사장",
                        null)));

        MockHttpServletRequestBuilder request = get("/api/festivals")
                .param("yearMonth", "2026-08")
                .param("size", "3");
        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].contentId").value("ongoing"))
                .andExpect(jsonPath("$.items[0].name").value("진행 중 축제"))
                .andExpect(jsonPath("$.items[0].startDate").value("2026-08-20"))
                .andExpect(jsonPath("$.items[0].endDate").value("2026-08-22"))
                .andExpect(jsonPath("$.items[0].region").value("서울"))
                .andExpect(jsonPath("$.items[0].imageUrl")
                        .value("https://example.test/ongoing.jpg"))
                .andExpect(jsonPath("$.items[0].inProgress").value(true))
                .andExpect(jsonPath("$.items[0].lat").doesNotExist())
                .andExpect(jsonPath("$.items[0].lng").doesNotExist())
                .andExpect(jsonPath("$.items[0].address").doesNotExist())
                .andExpect(jsonPath("$.items[0].source").doesNotExist())
                .andExpect(jsonPath("$.items[0].fetchedAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].cachedAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].contentId").value("unknown"))
                .andExpect(jsonPath("$.items[1].region").value(""))
                .andExpect(jsonPath("$.items[1].inProgress").value(false))
                .andExpect(jsonPath("$.items[2].contentId").value("upcoming"))
                .andExpect(jsonPath("$.items[2].region").value("부산"))
                .andExpect(jsonPath("$.items[2].inProgress").value(false));

        mockMvc.perform(get("/api/festivals")
                        .param("yearMonth", "2026-08")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        verify(festivalProvider, times(1)).searchStartingFrom(MONTH_START);
    }

    @Test
    void parameter를_생략하면_KST_현재_월과_기본_6건을_사용한다() throws Exception {
        List<Festival> festivals = IntStream.rangeClosed(1, 7)
                .mapToObj(index -> festival(
                        "festival-" + index,
                        "축제 " + index,
                        TODAY.plusDays(index),
                        TODAY.plusDays(index + 1L),
                        "경기도 수원시",
                        null))
                .toList();
        given(festivalProvider.searchStartingFrom(MONTH_START)).willReturn(festivals);

        mockMvc.perform(get("/api/festivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(6));

        verify(festivalProvider).searchStartingFrom(MONTH_START);
    }

    @Test
    void 잘못된_연월과_size는_VALIDATION_FAILED다() throws Exception {
        assertProblem(get("/api/festivals").param("yearMonth", "2026-8"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/festivals").param("yearMonth", "2026-13"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/festivals").param("size", "0"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/festivals").param("size", "21"), 400, "VALIDATION_FAILED");

        verifyNoInteractions(festivalProvider);
    }

    @Test
    void 정상_빈_결과는_200과_빈_items다() throws Exception {
        given(festivalProvider.searchStartingFrom(MONTH_START)).willReturn(List.of());

        mockMvc.perform(get("/api/festivals").param("yearMonth", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void 외부오류와_타임아웃을_502와_504로_구분한다() throws Exception {
        given(festivalProvider.searchStartingFrom(MONTH_START))
                .willThrow(new FestivalProviderException(Reason.ERROR))
                .willThrow(new FestivalProviderException(Reason.TIMEOUT));

        MockHttpServletRequestBuilder request =
                get("/api/festivals").param("yearMonth", "2026-08");
        assertProblem(request, 502, "EXTERNAL_API_ERROR");
        assertProblem(request, 504, "EXTERNAL_API_TIMEOUT");
    }

    @Test
    void OpenAPI에_parameter와_응답_스키마를_공개한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/festivals']['get']").exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/festivals']['get']['parameters']"
                                        + "[?(@.name == 'yearMonth' && @.required == false)]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$['paths']['/api/festivals']['get']['parameters']"
                                        + "[?(@.name == 'size' && @.required == false)]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$.components.schemas.HomeFestivalListResponse.properties.items")
                        .exists())
                .andExpect(jsonPath(
                                "$.components.schemas.HomeFestivalResponse.properties.region")
                        .exists())
                .andExpect(jsonPath(
                                "$.components.schemas.HomeFestivalResponse.properties.inProgress")
                        .exists());
    }

    private Festival festival(
            String contentId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String address,
            String imageUrl) {
        return new Festival(
                contentId,
                name,
                startDate,
                endDate,
                null,
                null,
                imageUrl,
                address);
    }

    private void assertProblem(
            MockHttpServletRequestBuilder request,
            int expectedStatus,
            String expectedCode) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.instance").value("/api/festivals"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedBusinessClock() {
            return Clock.fixed(
                    Instant.parse("2026-08-20T15:00:00Z"),
                    ClockConfig.KST);
        }
    }
}
