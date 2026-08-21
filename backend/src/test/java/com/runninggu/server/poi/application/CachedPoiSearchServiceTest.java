package com.runninggu.server.poi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.poi.application.PoiSourceException.Reason;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CachedPoiSearchServiceTest {

    private KakaoPoiSource kakaoSource;
    private KtoPoiSource ktoSource;
    private CachedPoiSearchService service;

    @BeforeEach
    void setUp() {
        kakaoSource = mock(KakaoPoiSource.class);
        ktoSource = mock(KtoPoiSource.class);
        service = new CachedPoiSearchService(kakaoSource, ktoSource, List.of());
    }

    @Test
    void 세_건_미만이면_같은_원천을_20km로_재조회하고_폴백으로_부족분을_채운다() {
        PoiSearchCriteria requested = criteria(PoiCategory.LODGING, 8_000, 3);
        PoiSearchCriteria expanded = requested.withRadius(20_000);
        given(kakaoSource.search(requested, 3))
                .willReturn(List.of(poi("호텔 A", PoiProvider.KAKAO, "36.49", "127.27", 900)));
        given(kakaoSource.search(expanded, 3)).willReturn(List.of(
                poi("호텔 A", PoiProvider.KAKAO, "36.490", "127.270", 900),
                poi("호텔 B", PoiProvider.KAKAO, "36.50", "127.28", 700)));
        given(ktoSource.search(expanded, 3)).willReturn(List.of(
                poi(" 호텔 A ", PoiProvider.KTO, "36.4900", "127.2700", 850),
                poi("호텔 C", PoiProvider.KTO, "36.48", "127.26", 200)));

        assertThat(service.search(requested).items())
                .extracting(Poi::name)
                .containsExactly("호텔 C", "호텔 B", "호텔 A");
        verify(kakaoSource).search(requested, 3);
        verify(kakaoSource).search(expanded, 3);
        verify(ktoSource).search(expanded, 3);
    }

    @Test
    void 첫_원천에서_요청_개수를_채우면_확대와_폴백을_호출하지_않는다() {
        PoiSearchCriteria criteria = criteria(PoiCategory.FOOD, 8_000, 3);
        given(kakaoSource.search(criteria, 3)).willReturn(List.of(
                poi("식당 A", PoiProvider.KAKAO, "36.49", "127.27", 100),
                poi("식당 B", PoiProvider.KAKAO, "36.50", "127.28", 200),
                poi("식당 C", PoiProvider.KAKAO, "36.51", "127.29", 300)));

        assertThat(service.search(criteria).items()).hasSize(3);

        verify(kakaoSource).search(criteria, 3);
        verify(kakaoSource, never()).search(criteria.withRadius(20_000), 3);
        verifyNoInteractions(ktoSource);
    }

    @Test
    void 첫_원천이_실패해도_폴백에서_한_건을_만들면_부분_성공이다() {
        PoiSearchCriteria criteria = criteria(PoiCategory.TOUR, 8_000, 8);
        given(ktoSource.search(criteria, 8))
                .willThrow(new PoiSourceException(Reason.ERROR));
        given(kakaoSource.search(criteria, 8)).willReturn(List.of(
                poi("관광지", PoiProvider.KAKAO, "36.49", "127.27", 100)));

        assertThat(service.search(criteria).items())
                .extracting(Poi::provider)
                .containsExactly(PoiProvider.KAKAO);
    }

    @Test
    void 모든_원천이_정상_빈_응답이면_빈_목록이다() {
        PoiSearchCriteria criteria = criteria(PoiCategory.TOUR, 8_000, 8);
        given(ktoSource.search(criteria, 8)).willReturn(List.of());
        given(ktoSource.search(criteria.withRadius(20_000), 8)).willReturn(List.of());
        given(kakaoSource.search(criteria.withRadius(20_000), 8)).willReturn(List.of());

        assertThat(service.search(criteria).items()).isEmpty();
    }

    @Test
    void 표시_항목이_없고_모든_실패가_타임아웃이면_504다() {
        PoiSearchCriteria criteria = criteria(PoiCategory.TOUR, 8_000, 8);
        given(ktoSource.search(criteria, 8))
                .willThrow(new PoiSourceException(Reason.TIMEOUT));
        given(kakaoSource.search(criteria, 8))
                .willThrow(new PoiSourceException(Reason.TIMEOUT));

        assertErrorCode(() -> service.search(criteria), ErrorCode.EXTERNAL_API_TIMEOUT);
    }

    @Test
    void 표시_항목이_없고_일반_실패가_섞이면_502다() {
        PoiSearchCriteria criteria = criteria(PoiCategory.TOUR, 8_000, 8);
        given(ktoSource.search(criteria, 8))
                .willThrow(new PoiSourceException(Reason.TIMEOUT));
        given(kakaoSource.search(criteria, 8))
                .willThrow(new PoiSourceException(Reason.ERROR));

        assertErrorCode(() -> service.search(criteria), ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void 두루누비_번들_장애는_NATURE의_카카오_결과를_막지_않는다() {
        NaturePoiBundleSource bundleSource = mock(NaturePoiBundleSource.class);
        service = new CachedPoiSearchService(kakaoSource, ktoSource, List.of(bundleSource));
        PoiSearchCriteria criteria = criteria(PoiCategory.NATURE, 20_000, 1);
        given(kakaoSource.search(criteria, 3)).willReturn(List.of(
                poi("공원", PoiProvider.KAKAO, "36.49", "127.27", 100)));
        given(bundleSource.search(criteria, 3)).willThrow(new IllegalStateException("동기화 전"));

        assertThat(service.search(criteria).items())
                .extracting(Poi::name)
                .containsExactly("공원");
    }

    private PoiSearchCriteria criteria(PoiCategory category, int radius, int size) {
        return new PoiSearchCriteria(
                category,
                new BigDecimal("36.4912"),
                new BigDecimal("127.2714"),
                radius,
                "",
                size);
    }

    private Poi poi(
            String name,
            PoiProvider provider,
            String lat,
            String lng,
            int distance) {
        return new Poi(
                name,
                PoiCategory.LODGING,
                provider,
                new BigDecimal(lat),
                new BigDecimal(lng),
                distance,
                "설명",
                "주소",
                "",
                null);
    }

    private void assertErrorCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
