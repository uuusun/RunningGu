package com.runninggu.server.savedcourse.application;

import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class SavedCourseViews {

    private SavedCourseViews() {}

    public record Saved(long id, boolean created) {}

    public record PageResult(
            List<Summary> content,
            int number,
            int size,
            long totalElements,
            boolean hasNext) {}

    public record Summary(
            long id,
            String courseName,
            BigDecimal distanceKm,
            int durationMin,
            int gainM,
            CourseDifficulty difficulty,
            CourseDataSource dataSource,
            String region,
            Instant savedAt) {}

    public record Details(
            long id,
            String courseName,
            BigDecimal distanceKm,
            int durationMin,
            int gainM,
            CourseDifficulty difficulty,
            CourseDataSource dataSource,
            String region,
            Instant savedAt,
            List<Integer> elevationProfileM,
            String pathPolyline,
            List<String> attributions) {}
}
