package com.runninggu.server.course.application;

import java.util.List;

public record CourseNearResult(
        List<CourseNearItem> items,
        List<CourseDegradedSource> degradedSources,
        List<String> attributions) {

    public CourseNearResult {
        items = List.copyOf(items);
        degradedSources = List.copyOf(degradedSources);
        attributions = List.copyOf(attributions);
    }
}
