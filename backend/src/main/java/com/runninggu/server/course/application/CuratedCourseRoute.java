package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;

public record CuratedCourseRoute(
        String routeId,
        String sourceKey,
        CourseDataSource dataSource,
        String name,
        int distanceM,
        BigDecimal lat,
        BigDecimal lng,
        CourseDifficulty difficulty,
        BigDecimal routeKm,
        int durationMin,
        int gainM,
        List<Integer> elevationProfileM,
        boolean shortfall,
        String pathPolyline,
        String sourceCourseId,
        String sido,
        String sigun,
        BigDecimal fullDistanceKm) implements CourseNearItem {

    public CuratedCourseRoute {
        elevationProfileM = List.copyOf(elevationProfileM);
    }
}
