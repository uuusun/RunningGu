package com.runninggu.server.course.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.course.application.CourseDetail;
import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CoursePoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CourseDetailResponseTest {
    @Test
    void 왕복으로_변환하지_않고_원본_전체_좌표_순서와_목록_값을_유지한다() {
        Instant syncedAt = Instant.parse("2026-09-05T00:00:00Z");
        Course course = course(new BigDecimal("17.8"), syncedAt);
        CourseDetailResponse response = CourseDetailResponse.from(
                new CourseDetail(course, List.of("완성된 원천 문구")));

        // Google Encoded Polyline의 알려진 3점 예제. 점 누락·순서 변경·왕복 생성 시 달라진다.
        assertThat(response.pathPolyline()).isEqualTo("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertThat(response.elevationProfileM()).containsExactly(10, 30, 20);
        assertThat(response.distanceKm()).isEqualByComparingTo("17.8");
        assertThat(response.durationMin()).isEqualTo(162);
        assertThat(response.difficulty()).isEqualTo(CourseDifficulty.HARD);
        assertThat(response.gainM()).isEqualTo(312);
        assertThat(response.syncedAt()).isEqualTo(syncedAt);
        assertThat(response.attributions()).containsExactly("완성된 원천 문구");
    }

    @Test
    void 짧은_코스도_최소_1분이고_GPX_ONLY는_동기화_시각이_없다() {
        CourseDetailResponse response = CourseDetailResponse.from(new CourseDetail(
                course(new BigDecimal("0.001"), null).asGpxOnly(), List.of("출처")));
        assertThat(response.durationMin()).isEqualTo(1);
        assertThat(response.dataSource()).isEqualTo(CourseDataSource.GPX_ONLY);
        assertThat(response.syncedAt()).isNull();
    }

    private Course course(BigDecimal distance, Instant syncedAt) {
        return new Course("C1", "source", CourseDataSource.API_GPX, "전체 코스", "서울", "종로구",
                distance, 312, CourseDifficulty.HARD, "비순환형", "요약", List.of(
                        point("38.5", "-120.2", "10"), point("40.7", "-120.95", "30"),
                        point("43.252", "-126.453", "20")), syncedAt);
    }

    private CoursePoint point(String lat, String lng, String elevation) {
        return new CoursePoint(new BigDecimal(lat), new BigDecimal(lng),
                new BigDecimal(elevation), BigDecimal.ZERO);
    }
}
