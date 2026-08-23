package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** KTO 전체 성공본만 새 불변 snapshot으로 만들어 한 번에 교체한다. (SPEC §5.8·§8.4) */
@Service
public class CourseSyncService {

    private static final Logger log = LoggerFactory.getLogger(CourseSyncService.class);
    private static final String DURUNUBI_SOURCE = "durunubi";

    private final CourseCatalog catalog;
    private final CourseMetadataProvider metadataProvider;
    private final Clock clock;
    private final AtomicBoolean synchronizing = new AtomicBoolean();

    public CourseSyncService(
            CourseCatalog catalog,
            CourseMetadataProvider metadataProvider,
            Clock clock) {
        this.catalog = catalog;
        this.metadataProvider = metadataProvider;
        this.clock = clock;
    }

    public CourseSyncResult synchronize() {
        int bundleCount = catalog.bundleSnapshot().courses().size();
        if (!synchronizing.compareAndSet(false, true)) {
            log.info("두루누비 메타 동기화를 건너뜁니다. reason=ALREADY_RUNNING bundleCount={}", bundleCount);
            return CourseSyncResult.skipped(bundleCount);
        }
        try {
            return synchronizeOnce();
        } finally {
            synchronizing.set(false);
        }
    }

    private CourseSyncResult synchronizeOnce() {
        Instant startedAt = clock.instant();
        CourseCatalogSnapshot bundle = catalog.bundleSnapshot();
        try {
            CourseMetadataBatch batch = metadataProvider.fetchAll();
            Instant completedAt = clock.instant();
            Map<String, CourseMetadata> metadata = batch.items();
            Set<String> bundleDurunubiIds = new HashSet<>();
            int matched = 0;
            int gpxOnly = 0;
            List<Course> merged = new ArrayList<>(bundle.courses().size());
            for (Course course : bundle.courses()) {
                if (!DURUNUBI_SOURCE.equals(course.source())) {
                    merged.add(course);
                    if (course.dataSource() == CourseDataSource.GPX_ONLY) {
                        gpxOnly++;
                    }
                    continue;
                }
                bundleDurunubiIds.add(course.courseId());
                CourseMetadata latest = metadata.get(course.courseId());
                if (latest == null) {
                    merged.add(course.asGpxOnly());
                    gpxOnly++;
                } else {
                    merged.add(merge(course, latest, completedAt));
                    matched++;
                }
            }
            int apiOnly = Math.toIntExact(metadata.keySet().stream()
                    .filter(id -> !bundleDurunubiIds.contains(id))
                    .count());
            CourseCatalogSnapshot next = new CourseCatalogSnapshot(bundle.sources(), merged);
            catalog.replace(next);

            long durationMs = Duration.between(startedAt, completedAt).toMillis();
            log.info(
                    "두루누비 메타 동기화 완료. success=true bundleCount={} ktoCount={} matched={} gpxOnly={} apiOnly={} invalidFields={} durationMs={}",
                    bundle.courses().size(),
                    batch.rawCount(),
                    matched,
                    gpxOnly,
                    apiOnly,
                    batch.invalidFieldCount(),
                    durationMs);
            return new CourseSyncResult(
                    true,
                    false,
                    bundle.courses().size(),
                    batch.rawCount(),
                    matched,
                    gpxOnly,
                    apiOnly,
                    batch.invalidFieldCount());
        } catch (CourseMetadataSyncException exception) {
            long durationMs = Duration.between(startedAt, clock.instant()).toMillis();
            log.warn(
                    "두루누비 메타 동기화 실패. success=false reason={} bundleCount={} durationMs={}",
                    exception.reason(),
                    bundle.courses().size(),
                    durationMs);
            return CourseSyncResult.failed(bundle.courses().size());
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(startedAt, clock.instant()).toMillis();
            log.error(
                    "두루누비 메타 동기화 중 내부 오류가 발생해 기존 snapshot을 유지합니다. bundleCount={} durationMs={}",
                    bundle.courses().size(),
                    durationMs,
                    exception);
            return CourseSyncResult.failed(bundle.courses().size());
        }
    }

    private Course merge(Course course, CourseMetadata metadata, Instant completedAt) {
        return new Course(
                course.courseId(),
                course.source(),
                CourseDataSource.API_GPX,
                metadata.courseName() == null ? course.courseName() : metadata.courseName(),
                course.sido(),
                course.sigun(),
                course.distanceKm(),
                course.gainM(),
                metadata.difficulty() == null ? course.difficulty() : metadata.difficulty(),
                metadata.cycle() == null ? course.cycle() : metadata.cycle(),
                metadata.summary() == null ? course.summary() : metadata.summary(),
                course.points(),
                completedAt);
    }
}
