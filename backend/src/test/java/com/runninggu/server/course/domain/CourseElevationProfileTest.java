package com.runninggu.server.course.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CourseElevationProfileTest {
    @Test
    void 전체_구간의_양끝을_포함해_100개로_균등_축약한다() {
        List<CoursePoint> points = IntStream.rangeClosed(0, 198)
                .mapToObj(index -> point(BigDecimal.valueOf(index)))
                .toList();
        assertThat(CourseElevationProfile.sample(points))
                .containsExactlyElementsOf(IntStream.range(0, 100)
                        .map(index -> index * 2).boxed().toList());
    }

    @Test
    void 짧은_구간은_순서와_음수_고도를_보존하고_정수로_반올림한다() {
        assertThat(CourseElevationProfile.sample(List.of(
                point(new BigDecimal("-1.5")), point(new BigDecimal("2.4")),
                point(new BigDecimal("1.5")))))
                .containsExactly(-2, 2, 2);
    }

    @Test
    void 점이_없으면_빈_고도이며_한_점도_처리한다() {
        assertThat(CourseElevationProfile.sample(List.of())).isEmpty();
        assertThat(CourseElevationProfile.sample(List.of(point(BigDecimal.TEN))))
                .containsExactly(10);
    }

    private CoursePoint point(BigDecimal elevation) {
        return new CoursePoint(BigDecimal.ZERO, BigDecimal.ZERO, elevation, BigDecimal.ZERO);
    }
}
