package com.runninggu.server.itinerary.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.itinerary.domain.BlockCategory;
import com.runninggu.server.itinerary.domain.GeneratedBlock;
import com.runninggu.server.itinerary.domain.ItineraryGenerator;
import com.runninggu.server.itinerary.domain.ItineraryPlan;
import com.runninggu.server.itinerary.domain.ItineraryRace;
import com.runninggu.server.itinerary.domain.PoiPools;
import com.runninggu.server.poi.application.CachedPoiSearchService;
import com.runninggu.server.poi.application.KakaoPoiSource;
import com.runninggu.server.poi.application.KtoPoiSource;
import com.runninggu.server.poi.application.PoiService;
import com.runninggu.server.poi.application.PoiSourceException;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 원천 결과 → 조회 상한 → 분류 풀 → 식사 생성까지 연결한다. (SPEC §5.6 · PR #382) */
class MealPoolRegressionTest {

    private static final BigDecimal LAT = new BigDecimal("36.49");
    private static final BigDecimal LNG = new BigDecimal("127.27");
    private KakaoPoiSource kakao;
    private KtoPoiSource kto;
    private ItineraryPoiPoolLoader loader;

    @BeforeEach
    void setUp() {
        kakao = mock(KakaoPoiSource.class);
        kto = mock(KtoPoiSource.class);
        loader = new ItineraryPoiPoolLoader(new PoiService(
                new CachedPoiSearchService(kakao, kto, List.of())));
    }

    @Test
    void 가까운_8곳이_주점이어도_9번째_일반_식당을_추천한다() {
        givenKakao(nearbyThenRestaurant("음식점 > 술집 > 호프,요리주점"));

        PoiPools pools = load();

        assertThat(pools.get(PoiCategory.FOOD)).extracting(place -> place.name())
                .containsExactly("일반 식당");
        assertThat(meal(pools).place().name()).isEqualTo("일반 식당");
    }

    @Test
    void 가까운_8곳이_후순위여도_뒤의_일반_식당을_풀에_남겨_우선한다() {
        givenKakao(nearbyThenRestaurant("음식점 > 패스트푸드"));

        PoiPools pools = load();

        assertThat(pools.get(PoiCategory.FOOD)).extracting(place -> place.name())
                .containsExactly("일반 식당", "앞쪽-1", "앞쪽-2", "앞쪽-3", "앞쪽-4",
                        "앞쪽-5", "앞쪽-6", "앞쪽-7");
        assertThat(meal(pools).place().name()).isEqualTo("일반 식당");
    }

    @Test
    void 같은_등급은_거리순으로_최대_8건을_유지한다() {
        List<Poi> items = new ArrayList<>();
        for (int index = 15; index >= 1; index--) {
            items.add(poi("식당-" + index, PoiProvider.KAKAO, "음식점 > 한식", index * 20));
        }
        givenKakao(items);

        assertThat(load().get(PoiCategory.FOOD)).extracting(place -> place.name())
                .containsExactly("식당-1", "식당-2", "식당-3", "식당-4",
                        "식당-5", "식당-6", "식당-7", "식당-8");
    }

    @Test
    void 정상_조회에서_전부_제외되면_편집_진입점을_안내한다() {
        givenKakao(List.of(poi("주점", PoiProvider.KAKAO, "음식점 > 술집", 20)));

        assertNoEligibleMeal(load());
    }

    @Test
    void 카카오_실패_후_KTO_미분류_결과만_있으면_부분_성공의_적격_0건으로_안내한다() {
        given(kakao.search(any(), anyInt()))
                .willThrow(new PoiSourceException(PoiSourceException.Reason.TIMEOUT));
        given(kto.search(any(), anyInt()))
                .willReturn(List.of(poi("미분류 장소", PoiProvider.KTO, "", 100)));

        assertNoEligibleMeal(load());
    }

    @Test
    void 원천_실패에_다른_결과도_없으면_조회_실패로_안내한다() {
        given(kakao.search(any(), anyInt()))
                .willThrow(new PoiSourceException(PoiSourceException.Reason.TIMEOUT));

        PoiPools pools = load();

        assertThat(pools.sources()).doesNotContainKey(PoiCategory.FOOD);
        assertThat(meal(pools).place()).isNull();
        assertThat(meal(pools).description()).isEqualTo("식당 정보를 불러오지 못했어요.");
    }

    private void assertNoEligibleMeal(PoiPools pools) {
        assertThat(pools.sources()).containsEntry(PoiCategory.FOOD, "LIVE");
        assertThat(meal(pools).place()).isNull();
        assertThat(meal(pools).description())
                .isEqualTo("추천할 식당을 찾지 못했어요. 편집에서 장소를 추가해 보세요.");
    }

    private List<Poi> nearbyThenRestaurant(String category) {
        List<Poi> items = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            items.add(poi("앞쪽-" + index, PoiProvider.KAKAO, category, index * 20));
        }
        items.add(poi("일반 식당", PoiProvider.KAKAO, "음식점 > 한식", 300));
        return items;
    }

    private void givenKakao(List<Poi> items) {
        // 실제 원천처럼 요청 상한만 반환한다. 8건 조회로 되돌리면 9번째 식당이 사라져 실패한다.
        given(kakao.search(any(), anyInt())).willAnswer(invocation ->
                items.stream().limit(invocation.<Integer>getArgument(1)).toList());
    }

    private PoiPools load() {
        return loader.load(List.of(PoiCategory.FOOD), LAT, LNG);
    }

    private GeneratedBlock meal(PoiPools pools) {
        LocalDate date = LocalDate.of(2026, 10, 10);
        ItineraryRace race = new ItineraryRace(
                1L, "테스트 대회", "대회장", null, date, LocalTime.of(8, 0), LAT, LNG);
        ItineraryPlan plan = new ItineraryPlan(
                race, null, ContestEventType.HALF, List.of(), date.minusDays(1), date);
        return new ItineraryGenerator().generate(plan, pools)
                .days().getFirst().blocks().stream()
                .filter(block -> block.category() == BlockCategory.FOOD)
                .findFirst().orElseThrow();
    }

    private Poi poi(String name, PoiProvider provider, String category, int distance) {
        return new Poi(name, PoiCategory.FOOD, provider, LAT, LNG, distance,
                "표시 설명", "주소", "", null, category);
    }
}
