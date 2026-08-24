package com.runninggu.server.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.runninggu.server.course.application.CourseMetadataSyncException.Reason;
import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CoursePoint;
import com.runninggu.server.course.domain.CourseSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CourseSyncServiceTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-22T03:00:00Z");

    @Test
    void 전체_KTO_성공본만_결합하고_API에_없는_번들_코스는_GPX_ONLY로_보존한다() {
        CourseCatalog catalog = catalog();
        LinkedHashMap<String, CourseMetadata> metadata = new LinkedHashMap<>();
        metadata.put("C1", new CourseMetadata(
                "C1", "최신 이름", CourseDifficulty.HARD, "순환형", "최신 요약"));
        metadata.put("API_ONLY", new CourseMetadata(
                "API_ONLY", "경로 없는 코스", CourseDifficulty.EASY, "순환형", "요약"));
        CourseSyncService service = service(
                catalog,
                () -> new CourseMetadataBatch(metadata, 2, 0));

        CourseSyncResult result = service.synchronize();

        assertThat(result.success()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.gpxOnlyCount()).isEqualTo(1);
        assertThat(result.apiOnlyCount()).isEqualTo(1);
        assertThat(catalog.snapshot().courses()).satisfiesExactly(
                course -> {
                    assertThat(course.courseName()).isEqualTo("최신 이름");
                    assertThat(course.dataSource()).isEqualTo(CourseDataSource.API_GPX);
                    assertThat(course.syncedAt()).isEqualTo(COMPLETED_AT);
                    assertThat(course.sido()).isEqualTo("서울");
                    assertThat(course.distanceKm()).isEqualByComparingTo("3.0");
                },
                course -> {
                    assertThat(course.courseId()).isEqualTo("C2");
                    assertThat(course.dataSource()).isEqualTo(CourseDataSource.GPX_ONLY);
                    assertThat(course.syncedAt()).isNull();
                });
    }

    @Test
    void 외부_전체조회가_실패하면_현재_snapshot을_그대로_유지한다() {
        CourseCatalog catalog = catalog();
        CourseCatalogSnapshot before = catalog.snapshot();
        CourseSyncService service = service(catalog, () -> {
            throw new CourseMetadataSyncException(Reason.TIMEOUT);
        });

        CourseSyncResult result = service.synchronize();

        assertThat(result.success()).isFalse();
        assertThat(catalog.snapshot()).isSameAs(before);
    }

    @Test
    void 최신_KTO_필드가_잘못되면_이전_KTO값이_아닌_번들값으로_되돌린다() {
        CourseCatalog catalog = catalog();
        service(catalog, () -> new CourseMetadataBatch(
                        Map.of("C1", new CourseMetadata(
                                "C1", "이전 API 이름", CourseDifficulty.HARD, "순환형", "이전 API 요약")),
                        1,
                        0))
                .synchronize();

        service(catalog, () -> new CourseMetadataBatch(
                        Map.of("C1", new CourseMetadata("C1", null, null, null, null)),
                        1,
                        4))
                .synchronize();

        Course course = catalog.snapshot().courses().getFirst();
        assertThat(course.courseName()).isEqualTo("번들 C1");
        assertThat(course.difficulty()).isEqualTo(CourseDifficulty.NORMAL);
        assertThat(course.cycle()).isEqualTo("비순환형");
        assertThat(course.summary()).isEqualTo("번들 요약");
    }

    @Test
    void 앞선_동기화가_실행중이면_겹치는_실행은_기다리지_않고_건너뛴다() throws Exception {
        CourseCatalog catalog = catalog();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CourseSyncService service = service(catalog, () -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트 동기화 해제 timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new CourseMetadataBatch(
                    Map.of("C1", new CourseMetadata(
                            "C1", "최신", CourseDifficulty.EASY, "순환형", "요약")),
                    1,
                    0);
        });
        CompletableFuture<CourseSyncResult> first = CompletableFuture.supplyAsync(service::synchronize);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        CourseSyncResult overlapping = service.synchronize();

        assertThat(overlapping.success()).isFalse();
        assertThat(overlapping.skipped()).isTrue();
        release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS).success()).isTrue();
    }

    private CourseSyncService service(
            CourseCatalog catalog,
            CourseMetadataProvider provider) {
        return new CourseSyncService(
                catalog,
                provider,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
    }

    private CourseCatalog catalog() {
        CourseSource source = new CourseSource("durunubi", "두루누비", "라이선스");
        return new CourseCatalog(new CourseCatalogSnapshot(
                List.of(source),
                List.of(course("C1", CourseDataSource.GPX_ONLY), course("C2", CourseDataSource.API_GPX))));
    }

    private Course course(String id, CourseDataSource dataSource) {
        return new Course(
                id,
                "durunubi",
                dataSource,
                "번들 " + id,
                "서울",
                "서울 종로구",
                new BigDecimal("3.0"),
                10,
                CourseDifficulty.NORMAL,
                "비순환형",
                "번들 요약",
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
