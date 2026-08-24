package com.runninggu.server.course.application;

import java.util.List;

public record OsmRouteCandidate(
        int seed,
        double distanceM,
        double gainM,
        double polylineDistanceM,
        double majorRoadDistanceM,
        int turnCount,
        List<OsmRouteCoordinate> coordinates) {

    public OsmRouteCandidate {
        coordinates = List.copyOf(coordinates);
    }
}
