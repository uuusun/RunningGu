package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.runninggu.server.poi.application.KakaoPoiSource;
import com.runninggu.server.poi.application.PoiSearchCriteria;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WalkingSpotServiceTest {

    @Test
    void 여섯_키워드를_조회해_시설과_소공원을_빼고_같은_공원은_대표_한곳으로_묶는다() {
        KakaoPoiSource source = mock(KakaoPoiSource.class);
        given(source.search(any(PoiSearchCriteria.class), eq(15))).willAnswer(invocation -> {
            PoiSearchCriteria criteria = invocation.getArgument(0);
            if (!criteria.query().equals("공원")) {
                return List.of();
            }
            return List.of(
                    poi("샛강생태공원 반딧불이생태관", 50, "여행 > 공원", "A"),
                    poi("샛강생태공원", 200, "여행 > 공원", "A 본원"),
                    poi("동네 어린이공원", 60, "여행 > 공원", "B"),
                    poi("여의도공원 주차장", 70, "여행 > 공원", "C"),
                    poi("공원 운영센터", 80, "공원관리운영 > 사무소", "D"),
                    poi("여의도공원", 300, "여행 > 관광명소 > 공원", "E"));
        });
        WalkingSpotService service = new WalkingSpotService(source);

        WalkingSpotSearchResult result = service.search(
                new BigDecimal("37.5"),
                new BigDecimal("127.0"));

        assertThat(result.degraded()).isFalse();
        assertThat(result.items()).extracting(WalkingSpot::name)
                .containsExactly("샛강생태공원", "여의도공원");
        assertThat(result.items().getFirst().distanceM()).isEqualTo(200);
        assertThat(result.items().getFirst().category()).isEqualTo("공원");

        ArgumentCaptor<PoiSearchCriteria> criteria =
                ArgumentCaptor.forClass(PoiSearchCriteria.class);
        verify(source, org.mockito.Mockito.times(6)).search(criteria.capture(), eq(15));
        assertThat(criteria.getAllValues()).extracting(PoiSearchCriteria::query)
                .containsExactly("공원", "산책로", "둘레길", "하천", "한강공원", "생태공원");
        assertThat(criteria.getAllValues()).allSatisfy(value -> {
            assertThat(value.radius()).isEqualTo(3_000);
            assertThat(value.size()).isEqualTo(15);
        });
        verifyNoMoreInteractions(source);
    }

    @Test
    void 일부_키워드_호출이_실패하면_성공_결과를_유지하고_degraded로_표시한다() {
        KakaoPoiSource source = mock(KakaoPoiSource.class);
        given(source.search(any(PoiSearchCriteria.class), eq(15))).willAnswer(invocation -> {
            PoiSearchCriteria criteria = invocation.getArgument(0);
            if (criteria.query().equals("공원")) {
                throw new PoiSourceException(PoiSourceException.Reason.ERROR);
            }
            if (criteria.query().equals("하천")) {
                return List.of(poi("수원천", 120, "여행 > 자연 > 하천", "수원"));
            }
            return List.of();
        });

        WalkingSpotSearchResult result = new WalkingSpotService(source).search(
                new BigDecimal("37.5"),
                new BigDecimal("127.0"));

        assertThat(result.degraded()).isTrue();
        assertThat(result.items()).extracting(WalkingSpot::name).containsExactly("수원천");
    }

    private Poi poi(String name, int distanceM, String category, String address) {
        return new Poi(
                name,
                PoiCategory.NATURE,
                PoiProvider.KAKAO,
                new BigDecimal("37.5"),
                new BigDecimal("127.0"),
                distanceM,
                category,
                address,
                "https://place.map.kakao.com/test",
                null);
    }
}
