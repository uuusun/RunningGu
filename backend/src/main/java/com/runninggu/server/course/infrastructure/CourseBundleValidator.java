package com.runninggu.server.course.infrastructure;

import com.runninggu.server.course.domain.CourseDataSource;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 생산자 계약을 다시 검증해 잘못된 경로 번들로 서버가 시작되지 않게 한다. (SPEC §5.8·§8.4) */
final class CourseBundleValidator {

    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> REGIONS = Set.of(
            "서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    private static final BigDecimal MIN_LAT = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LAT = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LNG = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LNG = BigDecimal.valueOf(180);

    private final int minimumCourseCount;

    CourseBundleValidator(int minimumCourseCount) {
        if (minimumCourseCount < 1) {
            throw new IllegalArgumentException("minimumCourseCount는 1 이상이어야 합니다.");
        }
        this.minimumCourseCount = minimumCourseCount;
    }

    void validate(CourseBundleFile bundle) {
        require(bundle != null, "번들 최상위 객체가 없습니다.");
        require(bundle.schemaVersion() == SCHEMA_VERSION, "지원하지 않는 schemaVersion입니다.");
        require(bundle.sources() != null && !bundle.sources().isEmpty(), "sources가 비어 있습니다.");
        require(bundle.courses() != null, "courses가 없습니다.");
        require(bundle.courses().size() >= minimumCourseCount, "코스 수가 승인된 하한보다 작습니다.");

        Set<String> sourceKeys = validateSources(bundle.sources());
        validateCourses(bundle.courses(), sourceKeys);
    }

    private Set<String> validateSources(List<CourseBundleFile.SourceEntry> sources) {
        Set<String> keys = new HashSet<>();
        String previous = null;
        for (int index = 0; index < sources.size(); index++) {
            CourseBundleFile.SourceEntry source = sources.get(index);
            require(source != null, "sources[" + index + "]가 null입니다.");
            requireText(source.key(), "sources[" + index + "].key");
            requireText(source.attribution(), "sources[" + index + "].attribution");
            requireText(source.license(), "sources[" + index + "].license");
            require(source.derivable(), "파생이 허용되지 않은 원천이 포함됐습니다: " + source.key());
            require(keys.add(source.key()), "원천 key가 중복됩니다: " + source.key());
            require(previous == null || previous.compareTo(source.key()) < 0, "sources가 key 오름차순이 아닙니다.");
            previous = source.key();
        }
        return keys;
    }

    private void validateCourses(
            List<CourseBundleFile.CourseEntry> courses,
            Set<String> sourceKeys) {
        Set<String> courseIds = new HashSet<>();
        String previous = null;
        int gpxOnlyCount = 0;
        for (int index = 0; index < courses.size(); index++) {
            CourseBundleFile.CourseEntry course = courses.get(index);
            String path = "courses[" + index + "]";
            require(course != null, path + "가 null입니다.");
            requireText(course.courseId(), path + ".courseId");
            requireText(course.source(), path + ".source");
            require(courseIds.add(course.courseId()), "courseId가 중복됩니다: " + course.courseId());
            require(previous == null || previous.compareTo(course.courseId()) < 0, "courses가 courseId 오름차순이 아닙니다.");
            previous = course.courseId();
            require(sourceKeys.contains(course.source()), path + "가 알 수 없는 source를 참조합니다.");
            require(course.dataSource() == CourseDataSource.API_GPX
                            || course.dataSource() == CourseDataSource.GPX_ONLY,
                    path + ".dataSource가 서비스 대상이 아닙니다.");
            if (course.dataSource() == CourseDataSource.GPX_ONLY) {
                gpxOnlyCount++;
            }

            requireNfcText(course.courseName(), path + ".courseName", false);
            requireNfcText(course.sido(), path + ".sido", false);
            require(REGIONS.contains(course.sido()), path + ".sido가 17개 시도 단축명이 아닙니다.");
            requireNfcText(course.sigun(), path + ".sigun", false);
            require(course.distanceKm() != null
                            && course.distanceKm().compareTo(BigDecimal.ZERO) > 0,
                    path + ".distanceKm는 양수여야 합니다.");
            require(course.distanceKm().stripTrailingZeros().scale() <= 1,
                    path + ".distanceKm는 소수점 한 자리까지여야 합니다.");
            require(course.gainM() != null && course.gainM() >= 0,
                    path + ".gainM은 0 이상이어야 합니다.");
            require(course.difficulty() != null, path + ".difficulty가 없습니다.");
            requireNfcText(course.cycle(), path + ".cycle", true);
            requireNfcText(course.summary(), path + ".summary", true);
            validatePoints(course.points(), path + ".points");
        }
        require(gpxOnlyCount > 0, "GPX_ONLY 코스가 없어 시드 누락 가능성이 있습니다.");
    }

    private void validatePoints(List<List<BigDecimal>> points, String path) {
        require(points != null && points.size() >= 2, path + "는 점이 2개 이상이어야 합니다.");
        BigDecimal previousGain = null;
        for (int index = 0; index < points.size(); index++) {
            List<BigDecimal> point = points.get(index);
            String pointPath = path + "[" + index + "]";
            require(point != null && point.size() == 4, pointPath + "는 숫자 4개여야 합니다.");
            require(point.stream().allMatch(value -> value != null), pointPath + "에 null이 있습니다.");
            BigDecimal lat = point.get(0);
            BigDecimal lng = point.get(1);
            BigDecimal cumulativeGain = point.get(3);
            require(between(lat, MIN_LAT, MAX_LAT), pointPath + "의 위도가 범위를 벗어났습니다.");
            require(between(lng, MIN_LNG, MAX_LNG), pointPath + "의 경도가 범위를 벗어났습니다.");
            require(cumulativeGain.compareTo(BigDecimal.ZERO) >= 0,
                    pointPath + "의 누적 상승고도가 음수입니다.");
            require(previousGain == null || cumulativeGain.compareTo(previousGain) >= 0,
                    path + "의 누적 상승고도가 감소합니다.");
            previousGain = cumulativeGain;
        }
    }

    private boolean between(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private void requireText(String value, String path) {
        require(value != null && !value.isBlank() && value.equals(value.strip()), path + "가 비어 있거나 정규화되지 않았습니다.");
    }

    private void requireNfcText(String value, String path, boolean emptyAllowed) {
        require(value != null, path + "가 없습니다.");
        require(emptyAllowed ? value.isEmpty() || !value.isBlank() : !value.isBlank(), path + "가 비어 있습니다.");
        require(value.equals(value.strip()), path + "에 앞뒤 공백이 있습니다.");
        require(value.equals(Normalizer.normalize(value, Normalizer.Form.NFC)), path + "가 NFC 문자열이 아닙니다.");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CourseBundleValidationException(message);
        }
    }
}
