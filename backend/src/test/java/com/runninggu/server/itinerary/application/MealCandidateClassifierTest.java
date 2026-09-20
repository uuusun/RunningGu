package com.runninggu.server.itinerary.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.itinerary.domain.MealSuitability;
import com.runninggu.server.poi.domain.Poi;
import com.runninggu.server.poi.domain.PoiCategory;
import com.runninggu.server.poi.domain.PoiProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MealCandidateClassifierTest {

    @ParameterizedTest
    @CsvSource({
        "'음식점 > 한식 > 육류,고기요리', PREFERRED",
        "'음식점 > 간식 > 제과,베이커리', SECONDARY",
        "'음식점 > 패스트푸드 > 햄버거', SECONDARY",
        "'음식점 > 술집 > 호프,요리주점', EXCLUDED",
        "'음식점 > 바', EXCLUDED",
        "'음식점 > 펍', EXCLUDED",
        "'음식점 > 한식 > 생맥주전문점', EXCLUDED",
        "'카페 > 커피전문점', UNKNOWN",
        "'', UNKNOWN"
    })
    void 카카오_업종_경로를_식사_후보_등급으로_분류한다(
            String sourceCategory,
            MealSuitability expected) {
        assertThat(MealCandidateClassifier.classify(
                        poi(PoiProvider.KAKAO, PoiCategory.FOOD, sourceCategory)))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "FD01, PREFERRED",
        "FD02, PREFERRED",
        "FD03, SECONDARY",
        "FD04, EXCLUDED",
        "FD05, UNKNOWN",
        "'', UNKNOWN"
    })
    void KTO_중분류_코드를_식사_후보_등급으로_분류한다(
            String sourceCategory,
            MealSuitability expected) {
        assertThat(MealCandidateClassifier.classify(
                        poi(PoiProvider.KTO, PoiCategory.FOOD, sourceCategory)))
                .isEqualTo(expected);
    }

    @Test
    void 표시용_설명은_업종_판정에_사용하지_않는다() {
        Poi poi = new Poi(
                "업소",
                PoiCategory.FOOD,
                PoiProvider.KAKAO,
                BigDecimal.ONE,
                BigDecimal.ONE,
                1,
                "음식점 > 한식",
                "주소",
                "",
                null,
                "음식점 > 술집 > 호프,요리주점");

        assertThat(MealCandidateClassifier.classify(poi))
                .isEqualTo(MealSuitability.EXCLUDED);
    }

    @Test
    void 식사_외_카테고리는_식사_판정으로_제외하지_않는다() {
        assertThat(MealCandidateClassifier.classify(
                        poi(PoiProvider.KAKAO, PoiCategory.TOUR, "여행 > 관광명소")))
                .isEqualTo(MealSuitability.PREFERRED);
    }

    private Poi poi(PoiProvider provider, PoiCategory category, String sourceCategory) {
        return new Poi(
                "업소",
                category,
                provider,
                BigDecimal.ONE,
                BigDecimal.ONE,
                1,
                "표시용 설명",
                "주소",
                "",
                null,
                sourceCategory);
    }
}
