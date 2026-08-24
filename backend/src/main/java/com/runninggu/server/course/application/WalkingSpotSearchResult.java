package com.runninggu.server.course.application;

import java.util.List;

public record WalkingSpotSearchResult(
        List<WalkingSpot> items,
        boolean degraded) {

    public WalkingSpotSearchResult {
        items = List.copyOf(items);
    }
}
