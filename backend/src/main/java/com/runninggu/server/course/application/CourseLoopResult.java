package com.runninggu.server.course.application;

import java.util.List;
import java.util.Optional;

public record CourseLoopResult(
        Optional<OsmGeneratedRoute> route,
        List<String> attributions) {

    public CourseLoopResult {
        route = route == null ? Optional.empty() : route;
        attributions = List.copyOf(attributions);
    }
}
