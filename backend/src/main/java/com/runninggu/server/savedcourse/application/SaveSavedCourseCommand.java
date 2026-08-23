package com.runninggu.server.savedcourse.application;

import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;

public record SaveSavedCourseCommand(
        String sourceCourseId,
        CourseDataSource dataSource,
        String courseName,
        String region,
        BigDecimal distanceKm,
        int durationMin,
        CourseDifficulty difficulty,
        int gainM,
        List<Integer> elevationProfileM,
        BigDecimal entryLat,
        BigDecimal entryLng,
        String pathPolyline) {}
