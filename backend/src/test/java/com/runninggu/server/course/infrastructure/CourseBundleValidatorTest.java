package com.runninggu.server.course.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CourseBundleValidatorTest {

    private final CourseBundleValidator validator = new CourseBundleValidator(1);

    @Test
    void 모르는_schema와_파생불가_원천을_거부한다() {
        CourseBundleFile valid = validBundle();
        assertInvalid(
                new CourseBundleFile(2, valid.sources(), valid.courses()),
                "schemaVersion");
        assertInvalid(
                new CourseBundleFile(
                        1,
                        List.of(source("durunubi", false)),
                        valid.courses()),
                "파생");
    }

    @Test
    void 원천과_courseId_중복을_거부한다() {
        CourseBundleFile valid = validBundle();
        assertInvalid(
                new CourseBundleFile(
                        1,
                        List.of(source("durunubi", true), source("durunubi", true)),
                        valid.courses()),
                "중복");
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course("C1", "durunubi", CourseDataSource.GPX_ONLY, validPoints()),
                                course("C1", "durunubi", CourseDataSource.GPX_ONLY, validPoints()))),
                "courseId가 중복");
    }

    @Test
    void 알수없는_원천과_허용되지않은_dataSource를_거부한다() {
        CourseBundleFile valid = validBundle();
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course("C1", "unknown", CourseDataSource.GPX_ONLY, validPoints()))),
                "알 수 없는 source");
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course("C1", "durunubi", null, validPoints()))),
                "서비스 대상");
    }

    @Test
    void 좌표_범위와_누적상승고도_감소를_거부한다() {
        CourseBundleFile valid = validBundle();
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course(
                                "C1",
                                "durunubi",
                                CourseDataSource.GPX_ONLY,
                                List.of(point("91", "127", "0"), point("37.1", "127.1", "1"))))),
                "위도");
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course(
                                "C1",
                                "durunubi",
                                CourseDataSource.GPX_ONLY,
                                List.of(point("37", "127", "2"), point("37.1", "127.1", "1"))))),
                "감소");
    }

    @Test
    void 점이_두개_미만이거나_GPX_ONLY가_없으면_거부한다() {
        CourseBundleFile valid = validBundle();
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course(
                                "C1",
                                "durunubi",
                                CourseDataSource.GPX_ONLY,
                                List.of(point("37", "127", "0"))))),
                "2개 이상");
        assertInvalid(
                new CourseBundleFile(
                        1,
                        valid.sources(),
                        List.of(course("C1", "durunubi", CourseDataSource.API_GPX, validPoints()))),
                "GPX_ONLY");
    }

    private CourseBundleFile validBundle() {
        return new CourseBundleFile(
                1,
                List.of(source("durunubi", true)),
                List.of(course("C1", "durunubi", CourseDataSource.GPX_ONLY, validPoints())));
    }

    private CourseBundleFile.SourceEntry source(String key, boolean derivable) {
        return new CourseBundleFile.SourceEntry(
                key,
                "두루누비 걷기길(한국관광공사)",
                "공공데이터포털 이용약관 — 출처표시",
                derivable);
    }

    private CourseBundleFile.CourseEntry course(
            String id,
            String source,
            CourseDataSource dataSource,
            List<List<BigDecimal>> points) {
        return new CourseBundleFile.CourseEntry(
                id,
                source,
                dataSource,
                "테스트 코스",
                "서울",
                "서울 종로구",
                new BigDecimal("3.0"),
                10,
                CourseDifficulty.NORMAL,
                "비순환형",
                "요약",
                points);
    }

    private List<List<BigDecimal>> validPoints() {
        return List.of(point("37", "127", "0"), point("37.1", "127.1", "1"));
    }

    private List<BigDecimal> point(String lat, String lng, String cumulativeGain) {
        return List.of(
                new BigDecimal(lat),
                new BigDecimal(lng),
                BigDecimal.ZERO,
                new BigDecimal(cumulativeGain));
    }

    private void assertInvalid(CourseBundleFile bundle, String message) {
        assertThatThrownBy(() -> validator.validate(bundle))
                .isInstanceOf(CourseBundleValidationException.class)
                .hasMessageContaining(message);
    }
}
