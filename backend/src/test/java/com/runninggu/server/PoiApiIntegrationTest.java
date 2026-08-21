package com.runninggu.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.poi.application.KakaoPoiSource;
import com.runninggu.server.poi.application.KtoPoiSource;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.application.PoiSourceException.Reason;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PoiApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private KakaoPoiSource kakaoSource;

    @MockitoBean
    private KtoPoiSource ktoSource;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache(CacheConfig.POI_CACHE).clear();
    }

    @Test
    void 공개_API가_정확한_POI_필드를_반환하고_정규화한_성공을_캐시한다() throws Exception {
        given(kakaoSource.search(any(), eq(8))).willReturn(List.of(
                poi("호텔 세종 가온", 1200, "https://example.test/hotel.jpg"),
                poi("호텔 나성", 900, null),
                poi("호텔 어진", 1500, null)));
        given(ktoSource.search(any(), eq(8))).willReturn(List.of());

        mockMvc.perform(get("/api/pois")
                        .param("category", "LODGING")
                        .param("lat", "36.4912")
                        .param("lng", "127.2714")
                        .param("query", "세종 호텔"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.source").value("LIVE"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].name").value("호텔 나성"))
                .andExpect(jsonPath("$.items[0].category").value("LODGING"))
                .andExpect(jsonPath("$.items[0].provider").value("KAKAO"))
                .andExpect(jsonPath("$.items[0].lat").value(36.4912))
                .andExpect(jsonPath("$.items[0].lng").value(127.2714))
                .andExpect(jsonPath("$.items[0].distanceM").value(900))
                .andExpect(jsonPath("$.items[0].description").value("숙박"))
                .andExpect(jsonPath("$.items[0].address").value("세종특별자치시"))
                .andExpect(jsonPath("$.items[0].url").value("https://place.map.kakao.com/1"))
                .andExpect(jsonPath("$.items[0].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.items[1].imageUrl")
                        .value("https://example.test/hotel.jpg"))
                .andExpect(jsonPath("$.items[0].placeId").doesNotExist())
                .andExpect(jsonPath("$.items[0].fetchedAt").doesNotExist())
                .andExpect(jsonPath("$.items[0].cachedAt").doesNotExist());

        mockMvc.perform(get("/api/pois")
                        .param("category", "LODGING")
                        .param("lat", "36.4912")
                        .param("lng", "127.2714")
                        .param("query", "  세종 호텔  "))
                .andExpect(status().isOk());

        verify(kakaoSource, times(1)).search(any(), eq(8));
        verify(ktoSource, times(1)).search(any(), eq(8));
    }

    @Test
    void 필수값과_범위와_query를_검증한다() throws Exception {
        assertProblem(get("/api/pois"), 400, "VALIDATION_FAILED");
        assertProblem(get("/api/pois")
                        .param("category", "UNKNOWN")
                        .param("lat", "37")
                        .param("lng", "127"),
                400,
                "VALIDATION_FAILED");
        assertProblem(request("90.1", "127", "8000", "8", null), 400, "VALIDATION_FAILED");
        assertProblem(request("37", "127", "0", "8", null), 400, "VALIDATION_FAILED");
        assertProblem(request("37", "127", "8000", "21", null), 400, "VALIDATION_FAILED");
        assertProblem(request("37", "127", "8000", "8", " 가 "), 400, "VALIDATION_FAILED");
    }

    @Test
    void 외부_오류_응답은_캐시하지_않는다() throws Exception {
        given(kakaoSource.search(any(), eq(8)))
                .willThrow(new PoiSourceException(Reason.ERROR));
        var request = get("/api/pois")
                .param("category", "CAFE")
                .param("lat", "36.4912")
                .param("lng", "127.2714");

        assertProblem(request, 502, "EXTERNAL_API_ERROR");
        assertProblem(get("/api/pois")
                        .param("category", "CAFE")
                        .param("lat", "36.4912")
                        .param("lng", "127.2714"),
                502,
                "EXTERNAL_API_ERROR");

        verify(kakaoSource, times(2)).search(any(), eq(8));
    }

    @Test
    void OpenAPI에_POI_경로와_필수_파라미터와_정확한_응답_필드를_공개한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['paths']['/api/pois']['get']").exists())
                .andExpect(jsonPath(
                                "$['paths']['/api/pois']['get']['parameters']"
                                        + "[?(@.name == 'category' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$['paths']['/api/pois']['get']['parameters']"
                                        + "[?(@.name == 'lat' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath(
                                "$['paths']['/api/pois']['get']['parameters']"
                                        + "[?(@.name == 'lng' && @.required == true)]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.components.schemas.PoiItemResponse.properties.name")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.PoiItemResponse.properties.category")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.PoiItemResponse.properties.provider")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.PoiItemResponse.properties.distanceM")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.PoiItemResponse.properties.imageUrl")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.PoiItemResponse.properties.placeId")
                        .doesNotExist());
    }

    private Poi poi(String name, int distance, String imageUrl) {
        return new Poi(
                name,
                PoiCategory.LODGING,
                PoiProvider.KAKAO,
                new BigDecimal("36.4912"),
                new BigDecimal("127.2714"),
                distance,
                "숙박",
                "세종특별자치시",
                "https://place.map.kakao.com/1",
                imageUrl);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String lat,
            String lng,
            String radius,
            String size,
            String query) {
        var request = get("/api/pois")
                .param("category", "TOUR")
                .param("lat", lat)
                .param("lng", lng)
                .param("radius", radius)
                .param("size", size);
        if (query != null) {
            request.param("query", query);
        }
        return request;
    }

    private void assertProblem(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int expectedStatus,
            String expectedCode) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.instance").value("/api/pois"))
                .andExpect(jsonPath("$.traceId").isString());
    }
}
