package com.runninggu.server.course.api;

import com.runninggu.server.course.application.CourseRegionCount;
import java.util.List;

public record CourseRegionsResponse(List<Item> items) {

    static CourseRegionsResponse from(List<CourseRegionCount> regions) {
        return new CourseRegionsResponse(regions.stream()
                .map(region -> new Item(region.region(), region.count()))
                .toList());
    }

    public record Item(String region, int count) {}
}
