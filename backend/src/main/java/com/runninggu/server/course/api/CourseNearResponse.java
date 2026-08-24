package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CourseDegradedSource;
import com.runninggu.server.course.application.CourseNearResult;
import java.util.List;

public record CourseNearResponse(
        List<CourseNearItemResponse> items,
        List<CourseDegradedSource> degradedSources,
        List<String> attributions) {

    public static CourseNearResponse from(CourseNearResult result) {
        return new CourseNearResponse(
                result.items().stream().map(CourseNearItemResponse::from).toList(),
                result.degradedSources(),
                result.attributions());
    }
}
