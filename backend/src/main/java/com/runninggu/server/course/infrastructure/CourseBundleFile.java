package com.runninggu.server.course.infrastructure;

import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;

record CourseBundleFile(
        int schemaVersion,
        List<SourceEntry> sources,
        List<CourseEntry> courses) {

    record SourceEntry(
            String key,
            String attribution,
            String license,
            boolean derivable) {}

    record CourseEntry(
            String courseId,
            String source,
            CourseDataSource dataSource,
            String courseName,
            String sido,
            String sigun,
            BigDecimal distanceKm,
            Integer gainM,
            CourseDifficulty difficulty,
            String cycle,
            String summary,
            List<List<BigDecimal>> points) {}
}
