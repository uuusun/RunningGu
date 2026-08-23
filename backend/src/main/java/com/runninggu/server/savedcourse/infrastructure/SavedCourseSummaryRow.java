package com.runninggu.server.savedcourse.infrastructure;

import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.time.Instant;

public record SavedCourseSummaryRow(
        long id,
        String courseName,
        BigDecimal distanceKm,
        int durationMin,
        int gainM,
        CourseDifficulty difficulty,
        CourseDataSource dataSource,
        String region,
        Instant savedAt) {}
