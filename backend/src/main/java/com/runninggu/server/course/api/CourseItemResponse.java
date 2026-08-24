package com.runninggu.server.course.api;

import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.time.Instant;

public record CourseItemResponse(
        String courseId,
        String courseName,
        String sido,
        String sigun,
        BigDecimal distanceKm,
        CourseDifficulty difficulty,
        int gainM,
        int durationMin,
        CourseDataSource dataSource,
        Instant syncedAt) {

    private static final double WALKING_METERS_PER_MINUTE = 110.0;

    static CourseItemResponse from(Course course) {
        int durationMin = Math.max(
                1,
                (int) Math.round(course.distanceKm().doubleValue()
                        * 1_000
                        / WALKING_METERS_PER_MINUTE));
        return new CourseItemResponse(
                course.courseId(),
                course.courseName(),
                course.sido(),
                course.sigun(),
                course.distanceKm(),
                course.difficulty(),
                course.gainM(),
                durationMin,
                course.dataSource(),
                course.syncedAt());
    }
}
