package com.runninggu.server.course.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseSource;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** PostgreSQL 대신 검증된 불변 snapshot을 원자적으로 제공한다. (SPEC §5.8·§8.4) */
public final class CourseCatalog {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    private static final Comparator<Course> COURSE_ORDER = Comparator
            .comparing(Course::distanceKm)
            .thenComparing(Course::courseId);
    private static final Comparator<CourseRegionCount> REGION_ORDER = Comparator
            .comparingInt(CourseRegionCount::count)
            .reversed()
            .thenComparing(CourseRegionCount::region);

    private final AtomicReference<CourseCatalogSnapshot> snapshot;
    private final CourseCatalogSnapshot bundleSnapshot;

    public CourseCatalog(CourseCatalogSnapshot initialSnapshot) {
        this.bundleSnapshot = initialSnapshot;
        this.snapshot = new AtomicReference<>(initialSnapshot);
    }

    public CourseCatalogSnapshot bundleSnapshot() {
        return bundleSnapshot;
    }

    public CourseCatalogSnapshot snapshot() {
        return snapshot.get();
    }

    public void replace(CourseCatalogSnapshot next) {
        snapshot.set(next);
    }

    public CoursePage find(String region, int page, int size) {
        validatePage(page, size);
        CourseCatalogSnapshot current = snapshot.get();
        String normalizedRegion = region == null
                ? null
                : Normalizer.normalize(region.strip(), Normalizer.Form.NFC);
        List<Course> filtered = current.courses().stream()
                .filter(course -> normalizedRegion == null || course.sido().equals(normalizedRegion))
                .sorted(COURSE_ORDER)
                .toList();

        long offset = (long) page * size;
        int fromIndex = offset >= filtered.size() ? filtered.size() : Math.toIntExact(offset);
        int toIndex = Math.min(fromIndex + size, filtered.size());
        List<Course> content = filtered.subList(fromIndex, toIndex);
        return new CoursePage(
                content,
                page,
                size,
                filtered.size(),
                toIndex < filtered.size(),
                attributions(current, content));
    }

    public List<CourseRegionCount> regions() {
        CourseCatalogSnapshot current = snapshot.get();
        Map<String, Long> counts = current.courses().stream()
                .collect(Collectors.groupingBy(Course::sido, Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> new CourseRegionCount(entry.getKey(), Math.toIntExact(entry.getValue())))
                .sorted(REGION_ORDER)
                .toList();
    }

    private List<String> attributions(
            CourseCatalogSnapshot current,
            List<Course> content) {
        Set<String> usedSources = content.stream()
                .map(Course::source)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return current.sources().stream()
                .filter(source -> usedSources.contains(source.key()))
                .map(CourseSource::attribution)
                .toList();
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "page는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
        }
    }
}
