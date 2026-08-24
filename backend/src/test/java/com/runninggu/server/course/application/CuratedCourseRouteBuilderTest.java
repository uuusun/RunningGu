package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CoursePoint;
import com.runninggu.server.course.domain.CourseSource;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CuratedCourseRouteBuilderTest {

    private final CuratedCourseRouteBuilder builder = new CuratedCourseRouteBuilder();

    @Test
    void 최근접점에서_더_길게_뻗는_방향을_골라_왕복_구간을_만든다() {
        Course course = course(
                "C1",
                List.of(
                        point("0.0000", "127.0000", "0", "0"),
                        point("0.0000", "127.0010", "10", "10"),
                        point("0.0000", "127.0030", "30", "30")));

        CuratedCourseRoute route = builder.build(
                        snapshot(course),
                        decimal("0.0000"),
                        decimal("127.0010"),
                        decimal("0.4"),
                        decimal("8"))
                .getFirst();

        assertThat(route.routeId()).isEqualTo("curated:C1");
        assertThat(route.name()).isEqualTo("테스트길 왕복");
        assertThat(route.distanceM()).isZero();
        assertThat(route.lat()).isEqualByComparingTo("0.0000");
        assertThat(route.lng()).isEqualByComparingTo("127.0010");
        assertThat(route.routeKm()).isBetween(decimal("0.43"), decimal("0.45"));
        assertThat(route.gainM()).isEqualTo(20);
        assertThat(route.difficulty()).isEqualTo(CourseDifficulty.NORMAL);
        assertThat(route.elevationProfileM()).containsExactly(10, 30, 10);
        assertThat(route.shortfall()).isFalse();
        assertThat(route.pathPolyline()).isNotBlank();
    }

    @Test
    void 생성_구간의_상승이_50m_per_km_이상이면_원본등급과_무관하게_제외한다() {
        Course hardPiece = course(
                "HARD-PIECE",
                List.of(
                        point("37.0000", "127.0000", "0", "0"),
                        point("37.0010", "127.0000", "20", "20")));

        assertThat(builder.build(
                        snapshot(hardPiece),
                        decimal("37.0000"),
                        decimal("127.0000"),
                        decimal("1"),
                        decimal("8")))
                .isEmpty();
    }

    @Test
    void 목표보다_짧은_경로를_표시하고_고도는_최대_100개로_축약한다() {
        java.util.ArrayList<CoursePoint> points = new java.util.ArrayList<>();
        BigDecimal gain = BigDecimal.ZERO;
        for (int index = 0; index < 60; index++) {
            points.add(new CoursePoint(
                    BigDecimal.ZERO,
                    BigDecimal.valueOf(127 + index * 0.0001),
                    BigDecimal.valueOf(index % 2),
                    gain));
            if (index % 2 == 1) {
                gain = gain.add(BigDecimal.ONE);
            }
        }
        Course shortCourse = course("SHORT", points);

        CuratedCourseRoute route = builder.build(
                        snapshot(shortCourse),
                        BigDecimal.ZERO,
                        decimal("127"),
                        decimal("21"),
                        decimal("8"))
                .getFirst();

        assertThat(route.shortfall()).isTrue();
        assertThat(route.elevationProfileM()).hasSize(100);
    }

    private CourseCatalogSnapshot snapshot(Course course) {
        return new CourseCatalogSnapshot(
                List.of(new CourseSource("test", "테스트 출처", "테스트 라이선스")),
                List.of(course));
    }

    private Course course(String id, List<CoursePoint> points) {
        return new Course(
                id,
                "test",
                CourseDataSource.GPX_ONLY,
                "테스트길",
                "서울",
                "종로구",
                decimal("10"),
                100,
                CourseDifficulty.EASY,
                "비순환형",
                "테스트",
                points,
                null);
    }

    private CoursePoint point(String lat, String lng, String elevation, String gain) {
        return new CoursePoint(
                decimal(lat),
                decimal(lng),
                decimal(elevation),
                decimal(gain));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
