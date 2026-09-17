package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CourseLoopResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CourseLoopResponse(
        @Schema(nullable = true) CourseNearItemResponse route,
        List<String> attributions) {

    public static CourseLoopResponse from(CourseLoopResult result) {
        return new CourseLoopResponse(
                result.route().map(CourseNearItemResponse::from).orElse(null),
                result.attributions());
    }
}
