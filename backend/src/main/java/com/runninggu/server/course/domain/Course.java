package com.runninggu.server.course.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 검증된 GPX geometry와 선택적으로 결합한 최신 KTO 메타를 나타낸다. (SPEC §5.8·§8.4) */
public record Course(
        String courseId,
        String source,
        CourseDataSource dataSource,
        String courseName,
        String sido,
        String sigun,
        BigDecimal distanceKm,
        int gainM,
        CourseDifficulty difficulty,
        String cycle,
        String summary,
        List<CoursePoint> points,
        Instant syncedAt) {

    public Course {
        Objects.requireNonNull(courseId);
        Objects.requireNonNull(source);
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(courseName);
        Objects.requireNonNull(sido);
        Objects.requireNonNull(sigun);
        Objects.requireNonNull(distanceKm);
        Objects.requireNonNull(difficulty);
        Objects.requireNonNull(cycle);
        Objects.requireNonNull(summary);
        points = List.copyOf(points);
    }

    public Course asGpxOnly() {
        return new Course(
                courseId,
                source,
                CourseDataSource.GPX_ONLY,
                courseName,
                sido,
                sigun,
                distanceKm,
                gainM,
                difficulty,
                cycle,
                summary,
                points,
                null);
    }
}
