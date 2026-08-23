package com.runninggu.server.savedcourse.api;

import com.runninggu.server.savedcourse.application.SavedCourseViews.PageResult;
import com.runninggu.server.savedcourse.application.SavedCourseViews.Summary;
import com.runninggu.server.savedcourse.domain.CourseDataSource;
import com.runninggu.server.savedcourse.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SavedCourseListResponse(List<Item> content, Page page) {

    public static SavedCourseListResponse from(PageResult result) {
        return new SavedCourseListResponse(
                result.content().stream().map(Item::from).toList(),
                new Page(result.number(), result.size(), result.totalElements(), result.hasNext()));
    }

    public record Item(
            long id,
            String courseName,
            BigDecimal distanceKm,
            int durationMin,
            int gainM,
            CourseDifficulty difficulty,
            CourseDataSource dataSource,
            String region,
            Instant savedAt) {

        private static Item from(Summary summary) {
            return new Item(
                    summary.id(),
                    summary.courseName(),
                    summary.distanceKm(),
                    summary.durationMin(),
                    summary.gainM(),
                    summary.difficulty(),
                    summary.dataSource(),
                    summary.region(),
                    summary.savedAt());
        }
    }

    public record Page(int number, int size, long totalElements, boolean hasNext) {}
}
