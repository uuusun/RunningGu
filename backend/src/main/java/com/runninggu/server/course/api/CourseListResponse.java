package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CoursePage;
import java.util.List;

public record CourseListResponse(
        List<CourseItemResponse> content,
        PageResponse page,
        List<String> attributions) {

    static CourseListResponse from(CoursePage result) {
        return new CourseListResponse(
                result.content().stream().map(CourseItemResponse::from).toList(),
                new PageResponse(
                        result.number(),
                        result.size(),
                        result.totalElements(),
                        result.hasNext()),
                result.attributions());
    }

    public record PageResponse(
            int number,
            int size,
            long totalElements,
            boolean hasNext) {}
}
