package com.runninggu.server.savedcourse.api;

import com.runninggu.server.savedcourse.application.SavedCourseViews.Details;
import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SavedCourseDetailResponse(
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
        List<String> attributions) {

    public static SavedCourseDetailResponse from(Details details) {
        return new SavedCourseDetailResponse(
                details.id(),
                details.courseName(),
                details.distanceKm(),
                details.durationMin(),
                details.gainM(),
                details.difficulty(),
                details.dataSource(),
                details.region(),
                details.savedAt(),
                details.elevationProfileM(),
                details.pathPolyline(),
                details.attributions());
    }
}
