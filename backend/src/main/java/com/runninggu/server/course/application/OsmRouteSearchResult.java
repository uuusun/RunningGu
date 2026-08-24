package com.runninggu.server.course.application;

import java.util.Optional;

public record OsmRouteSearchResult(
        Optional<OsmGeneratedRoute> route,
        boolean degraded) {

    public OsmRouteSearchResult {
        route = route == null ? Optional.empty() : route;
    }

    public static OsmRouteSearchResult normal(Optional<OsmGeneratedRoute> route) {
        return new OsmRouteSearchResult(route, false);
    }

    public static OsmRouteSearchResult degraded(Optional<OsmGeneratedRoute> route) {
        return new OsmRouteSearchResult(route, true);
    }
}
