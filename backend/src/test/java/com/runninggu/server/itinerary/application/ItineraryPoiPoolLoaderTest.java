package com.runninggu.server.itinerary.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.itinerary.domain.PoiPools;
import com.runninggu.server.poi.application.PoiSearchResult;
import com.runninggu.server.poi.application.PoiService;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ItineraryPoiPoolLoaderTest {

    private static final BigDecimal LAT = new BigDecimal("36.4912000");
    private static final BigDecimal LNG = new BigDecimal("127.2714000");

    private PoiService poiService;
    private ItineraryPoiPoolLoader loader;

    @BeforeEach
    void setUp() {
        poiService = mock(PoiService.class);
        loader = new ItineraryPoiPoolLoader(poiService);
    }

    @Test
    void 카테고리마다_반경_8km에서_여덟_건을_조회해_내부_LIVE_원천을_기록한다() {
        givenSearch(PoiCategory.FOOD, new PoiSearchResult(List.of(poi("식당", PoiCategory.FOOD))));
        givenSearch(PoiCategory.TOUR, new PoiSearchResult(List.of(poi("공원", PoiCategory.TOUR))));

        PoiPools pools = loader.load(List.of(PoiCategory.FOOD, PoiCategory.TOUR), LAT, LNG);

        assertThat(pools.get(PoiCategory.FOOD)).extracting(place -> place.name())
                .containsExactly("식당");
        assertThat(pools.get(PoiCategory.TOUR)).extracting(place -> place.name())
                .containsExactly("공원");
        assertThat(pools.sources())
                .containsEntry(PoiCategory.FOOD, "LIVE")
                .containsEntry(PoiCategory.TOUR, "LIVE");
        verifySearch(PoiCategory.FOOD);
        verifySearch(PoiCategory.TOUR);
    }

    @Test
    void 외부_API_오류는_해당_카테고리만_빈_풀로_낮춘다() {
        givenSearch(PoiCategory.FOOD, new PoiSearchResult(List.of(poi("식당", PoiCategory.FOOD))));
        given(poiService.search(
                        eq(PoiCategory.TOUR),
                        eq(LAT),
                        eq(LNG),
                        eq(8_000),
                        isNull(),
                        eq(8)))
                .willThrow(new ApiException(
                        ErrorCode.EXTERNAL_API_TIMEOUT,
                        "관광지 원천 timeout"));

        PoiPools pools = loader.load(List.of(PoiCategory.FOOD, PoiCategory.TOUR), LAT, LNG);

        assertThat(pools.get(PoiCategory.FOOD)).hasSize(1);
        assertThat(pools.get(PoiCategory.TOUR)).isEmpty();
        assertThat(pools.sources()).containsKey(PoiCategory.FOOD);
        assertThat(pools.sources()).doesNotContainKey(PoiCategory.TOUR);
    }

    @Test
    void 외부_장애가_아닌_예외는_생성_실패로_전파한다() {
        given(poiService.search(
                        eq(PoiCategory.FOOD),
                        eq(LAT),
                        eq(LNG),
                        eq(8_000),
                        isNull(),
                        eq(8)))
                .willThrow(new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "내부 호출 계약 오류"));

        assertThatThrownBy(() -> loader.load(List.of(PoiCategory.FOOD), LAT, LNG))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private void givenSearch(PoiCategory category, PoiSearchResult result) {
        given(poiService.search(
                        eq(category),
                        eq(LAT),
                        eq(LNG),
                        eq(8_000),
                        isNull(),
                        eq(8)))
                .willReturn(result);
    }

    private void verifySearch(PoiCategory category) {
        verify(poiService).search(
                eq(category),
                eq(LAT),
                eq(LNG),
                eq(8_000),
                isNull(),
                eq(8));
    }

    private Poi poi(String name, PoiCategory category) {
        return new Poi(
                name,
                category,
                PoiProvider.KAKAO,
                LAT,
                LNG,
                500,
                "설명",
                "세종특별자치시",
                "https://place.map.kakao.com/1",
                null);
    }
}
