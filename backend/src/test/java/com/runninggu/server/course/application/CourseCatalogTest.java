package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CoursePoint;
import com.runninggu.server.course.domain.CourseSource;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CourseCatalogTest {

    private CourseCatalog catalog;

    @BeforeEach
    void setUp() {
        List<CourseSource> sources = List.of(
                new CourseSource("a", "원천 A", "라이선스 A"),
                new CourseSource("b", "원천 B", "라이선스 B"));
        catalog = new CourseCatalog(new CourseCatalogSnapshot(sources, List.of(
                course("C3", "b", "서울", "4.0"),
                course("C1", "a", "서울", "3.0"),
                course("C2", "a", "부산", "3.0"))));
    }

    @Test
    void 지역을_정규화하고_거리와_ID로_정렬하며_현재_페이지_출처만_반환한다() {
        String nfdRegion = Normalizer.normalize("서울", Normalizer.Form.NFD);
        CoursePage result = catalog.find("  " + nfdRegion + "  ", 0, 1);

        assertThat(result.content()).extracting(Course::courseId).containsExactly("C1");
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.attributions()).containsExactly("원천 A");
    }

    @Test
    void 마지막을_넘은_페이지는_200에_사용할_빈_내용과_빈_출처를_반환한다() {
        CoursePage result = catalog.find(null, 10, 2);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.attributions()).isEmpty();
    }

    @Test
    void 지역_집계는_건수_내림차순과_지역_오름차순이다() {
        assertThat(catalog.regions())
                .containsExactly(
                        new CourseRegionCount("서울", 2),
                        new CourseRegionCount("부산", 1));
    }

    @Test
    void 잘못된_페이지와_크기는_VALIDATION_FAILED다() {
        assertValidationFailed(() -> catalog.find(null, -1, 20));
        assertValidationFailed(() -> catalog.find(null, 0, 0));
        assertValidationFailed(() -> catalog.find(null, 0, 51));
    }

    private void assertValidationFailed(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private Course course(String id, String source, String sido, String distanceKm) {
        return new Course(
                id,
                source,
                CourseDataSource.GPX_ONLY,
                "코스 " + id,
                sido,
                sido + " 시군구",
                new BigDecimal(distanceKm),
                10,
                CourseDifficulty.NORMAL,
                "비순환형",
                "요약",
                List.of(
                        new CoursePoint(
                                new BigDecimal("37.0"),
                                new BigDecimal("127.0"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO),
                        new CoursePoint(
                                new BigDecimal("37.1"),
                                new BigDecimal("127.1"),
                                BigDecimal.ONE,
                                BigDecimal.ONE)),
                null);
    }
}
