package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CourseLoopServiceTest {

    private final CachedCourseLoopGeometryQuery geometryQuery =
            mock(CachedCourseLoopGeometryQuery.class);
    private final CourseLoopService service = new CourseLoopService(geometryQuery);

    @Test
    void 출발지는_거리_계산에만_쓰고_진입점으로_geometry를_조회한다() {
        BigDecimal entryLat = new BigDecimal("37.5264");
        BigDecimal entryLng = new BigDecimal("126.9227");
        BigDecimal targetKm = new BigDecimal("5");
        given(geometryQuery.find(entryLat, entryLng, targetKm))
                .willReturn(new CourseLoopGeometryResult(Optional.of(geometry())));

        CourseLoopResult result = service.find(
                new BigDecimal("37.5200"),
                new BigDecimal("126.9200"),
                entryLat,
                entryLng,
                targetKm,
                "여의도공원");

        assertThat(result.route()).hasValueSatisfying(route -> {
            assertThat(route.name()).isEqualTo("여의도공원 주변 5km 평지 러닝코스");
            assertThat(route.distanceM()).isPositive();
            assertThat(route.lat()).isEqualByComparingTo("37.5264");
            assertThat(route.lng()).isEqualByComparingTo("126.9227");
        });
        assertThat(result.attributions()).containsExactly("© OpenStreetMap contributors");
        verify(geometryQuery).find(entryLat, entryLng, targetKm);
    }

    @Test
    void 정상_0건은_빈_경로와_빈_출처다() {
        given(geometryQuery.find(
                        new BigDecimal("37.5264"),
                        new BigDecimal("126.9227"),
                        new BigDecimal("5")))
                .willReturn(new CourseLoopGeometryResult(Optional.empty()));

        CourseLoopResult result = service.find(
                new BigDecimal("37.5200"),
                new BigDecimal("126.9200"),
                new BigDecimal("37.5264"),
                new BigDecimal("126.9227"),
                new BigDecimal("5"),
                null);

        assertThat(result.route()).isEmpty();
        assertThat(result.attributions()).isEmpty();
    }

    @Test
    void 잘못된_진입점과_목표거리는_geometry를_조회하기_전에_거부한다() {
        assertValidationFailure(new BigDecimal("91"), new BigDecimal("5"));
        assertValidationFailure(new BigDecimal("37.5"), new BigDecimal("1.25"));
        verifyNoInteractions(geometryQuery);
    }

    private void assertValidationFailure(BigDecimal entryLat, BigDecimal targetKm) {
        assertThatThrownBy(() -> service.find(
                        new BigDecimal("37.5200"),
                        new BigDecimal("126.9200"),
                        entryLat,
                        new BigDecimal("126.9227"),
                        targetKm,
                        null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    private OsmRouteGeometry geometry() {
        return new OsmRouteGeometry(
                new BigDecimal("37.5264"),
                new BigDecimal("126.9227"),
                CourseDifficulty.EASY,
                new BigDecimal("5.06"),
                21,
                List.of(12, 13, 12),
                "???");
    }
}
