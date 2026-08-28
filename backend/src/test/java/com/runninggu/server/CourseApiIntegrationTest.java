package com.runninggu.server;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.course.application.OsmGeneratedRoute;
import com.runninggu.server.course.application.OsmRouteGenerator;
import com.runninggu.server.course.application.OsmRouteSearchResult;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.poi.application.KakaoPoiSource;
import com.runninggu.server.poi.application.PoiSearchCriteria;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@AutoConfigureMockMvc
class CourseApiIntegrationTest extends PostgreSqlContainerSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private KakaoPoiSource kakaoPoiSource;

    @MockitoBean
    private OsmRouteGenerator osmRouteGenerator;

    @BeforeEach
    void resetWalkingSource() {
        reset(kakaoPoiSource);
        reset(osmRouteGenerator);
        given(osmRouteGenerator.generate(any(), any(), any()))
                .willReturn(OsmRouteSearchResult.normal(Optional.empty()));
        cacheManager.getCache(CacheConfig.WALKING_SPOTS_CACHE).clear();
    }

    @Test
    void 게스트에게_번들_코스를_거리와_ID순으로_기본_페이지에_반환한다() throws Exception {
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(4)))
                .andExpect(jsonPath("$.content[0].courseId").value("C001"))
                .andExpect(jsonPath("$.content[1].courseId").value("C004"))
                .andExpect(jsonPath("$.content[2].courseId").value("C003"))
                .andExpect(jsonPath("$.content[3].courseId").value("C002"))
                .andExpect(jsonPath("$.content[0].durationMin").value(27))
                .andExpect(jsonPath("$.content[0].dataSource").value("API_GPX"))
                .andExpect(jsonPath("$.content[0].syncedAt").value(nullValue()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath(
                        "$.attributions",
                        contains("두루누비 걷기길(한국관광공사)", "테스트 트레일 원천")));
    }

    @Test
    void 지역은_NFC와_앞뒤공백을_정규화하고_sido와_정확히_일치시킨다() throws Exception {
        mockMvc.perform(get("/api/courses").param("region", "  서울  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].courseId").value("C001"))
                .andExpect(jsonPath("$.content[1].courseId").value("C004"))
                .andExpect(jsonPath("$.content[2].courseId").value("C003"))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void 마지막을_넘은_페이지는_빈_content와_빈_출처로_200을_반환한다() throws Exception {
        mockMvc.perform(get("/api/courses")
                        .param("page", "10")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.number").value(10))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(4))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.attributions", hasSize(0)));
    }

    @Test
    void 지역_집계는_건수_내림차순이며_합계가_전체_코스수와_같다() throws Exception {
        mockMvc.perform(get("/api/courses/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].region").value("서울"))
                .andExpect(jsonPath("$.items[0].count").value(3))
                .andExpect(jsonPath("$.items[1].region").value("부산"))
                .andExpect(jsonPath("$.items[1].count").value(1));
    }

    @Test
    void page와_size_범위를_벗어나면_VALIDATION_FAILED다() throws Exception {
        assertValidationFailed("page", "-1");
        assertValidationFailed("size", "0");
        assertValidationFailed("size", "51");
    }

    @Test
    void 출발지_주변_큐레이션_경로와_카카오_장소를_거리순으로_통합한다() throws Exception {
        given(kakaoPoiSource.search(any(PoiSearchCriteria.class), eq(15)))
                .willReturn(List.of(walkingPoi("종로공원", 100)));

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "37.5700")
                        .param("lng", "126.9800")
                        .param("targetKm", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].kind").value("ROUTE"))
                .andExpect(jsonPath("$.items[0].routeId").value("curated:C001"))
                .andExpect(jsonPath("$.items[0].name").value("서울 짧은길 왕복"))
                .andExpect(jsonPath("$.items[0].dataSource").value("API_GPX"))
                .andExpect(jsonPath("$.items[0].difficulty").value("EASY"))
                .andExpect(jsonPath("$.items[0].sourceCourseId").value("C001"))
                .andExpect(jsonPath("$.items[0].sido").value("서울"))
                .andExpect(jsonPath("$.items[0].fullDistanceKm").value(3.0))
                .andExpect(jsonPath("$.items[0].pathPolyline").isString())
                .andExpect(jsonPath("$.items[0].category").doesNotExist())
                .andExpect(jsonPath("$.items[1].kind").value("PLACE"))
                .andExpect(jsonPath("$.items[1].name").value("종로공원"))
                .andExpect(jsonPath("$.items[1].category").value("공원"))
                .andExpect(jsonPath("$.items[1].routeId").doesNotExist())
                .andExpect(jsonPath("$.degradedSources", hasSize(0)))
                .andExpect(jsonPath(
                        "$.attributions",
                        contains("두루누비 걷기길(한국관광공사)", "카카오 로컬")));
        verifyNoInteractions(osmRouteGenerator);
    }

    @Test
    void 적격_큐레이션이_없으면_OSM_경로를_생성하고_원본_코스_필드는_생략한다()
            throws Exception {
        given(osmRouteGenerator.generate(any(), any(), any()))
                .willReturn(OsmRouteSearchResult.normal(Optional.of(osmRoute())));
        given(kakaoPoiSource.search(any(PoiSearchCriteria.class), eq(15)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "0")
                        .param("lng", "0")
                        .param("targetKm", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind").value("ROUTE"))
                .andExpect(jsonPath("$.items[0].routeId").value("osm:123456789abc"))
                .andExpect(jsonPath("$.items[0].name").value("출발지 주변 5km 평지 러닝코스"))
                .andExpect(jsonPath("$.items[0].dataSource").value("OSM_GENERATED"))
                .andExpect(jsonPath("$.items[0].sourceCourseId").doesNotExist())
                .andExpect(jsonPath("$.items[0].sido").doesNotExist())
                .andExpect(jsonPath("$.items[0].sigun").doesNotExist())
                .andExpect(jsonPath("$.items[0].fullDistanceKm").doesNotExist())
                .andExpect(jsonPath("$.degradedSources", hasSize(0)))
                .andExpect(jsonPath(
                        "$.attributions",
                        contains("© OpenStreetMap contributors")));
    }

    @Test
    void GraphHopper가_실패해도_장소가_있으면_OSM_degraded_부분성공이다()
            throws Exception {
        given(osmRouteGenerator.generate(any(), any(), any()))
                .willReturn(OsmRouteSearchResult.degraded(Optional.empty()));
        given(kakaoPoiSource.search(any(PoiSearchCriteria.class), eq(15)))
                .willReturn(List.of(walkingPoi("근처공원", 100)));

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "0")
                        .param("lng", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind").value("PLACE"))
                .andExpect(jsonPath("$.degradedSources", contains("OSM")))
                .andExpect(jsonPath("$.attributions", contains("카카오 로컬")));
    }

    @Test
    void 카카오_실패에도_경로가_있으면_부분성공_200이다() throws Exception {
        given(kakaoPoiSource.search(any(PoiSearchCriteria.class), eq(15)))
                .willThrow(new PoiSourceException(PoiSourceException.Reason.ERROR));

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "37.5700")
                        .param("lng", "126.9800")
                        .param("targetKm", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].kind").value("ROUTE"))
                .andExpect(jsonPath("$.degradedSources", contains("KAKAO")))
                .andExpect(jsonPath(
                        "$.attributions",
                        contains("두루누비 걷기길(한국관광공사)")));
    }

    @Test
    void 모든_원천이_정상이고_결과가_없으면_빈_200이다() throws Exception {
        given(kakaoPoiSource.search(any(PoiSearchCriteria.class), eq(15)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "0")
                        .param("lng", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.degradedSources", hasSize(0)))
                .andExpect(jsonPath("$.attributions", hasSize(0)));
    }

    @Test
    void 표시할_항목이_없고_카카오가_실패하면_COURSE_SOURCES_UNAVAILABLE이다()
            throws Exception {
        given(kakaoPoiSource.search(any(PoiSearchCriteria.class), eq(15)))
                .willThrow(new PoiSourceException(PoiSourceException.Reason.TIMEOUT));

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "0")
                        .param("lng", "0"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COURSE_SOURCES_UNAVAILABLE"));
    }

    @Test
    void 출발지_주변_요청값_범위를_검증한다() throws Exception {
        assertNearValidationFailed("targetKm", "1.25");
        assertNearValidationFailed("targetKm", "21.5");
        assertNearValidationFailed("radiusKm", "0");
        assertNearValidationFailed("size", "13");

        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "91")
                        .param("lng", "127"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private void assertValidationFailed(String name, String value) throws Exception {
        mockMvc.perform(get("/api/courses").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/courses"));
    }

    private void assertNearValidationFailed(String name, String value) throws Exception {
        mockMvc.perform(get("/api/courses/near")
                        .param("lat", "37.57")
                        .param("lng", "126.98")
                        .param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Poi walkingPoi(String name, int distanceM) {
        return new Poi(
                name,
                PoiCategory.NATURE,
                PoiProvider.KAKAO,
                new BigDecimal("37.571"),
                new BigDecimal("126.981"),
                distanceM,
                "여행 > 자연 > 공원",
                "서울 종로구",
                "https://place.map.kakao.com/test",
                null);
    }

    private OsmGeneratedRoute osmRoute() {
        return new OsmGeneratedRoute(
                "osm:123456789abc",
                CourseDataSource.OSM_GENERATED,
                "출발지 주변 5km 평지 러닝코스",
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                CourseDifficulty.EASY,
                new BigDecimal("5.02"),
                46,
                20,
                List.of(10, 20, 10),
                false,
                "???");
    }
}
