package com.runninggu.server.course.application;

import java.math.BigDecimal;
import java.util.Optional;

public interface OsmRoundTripSource {

    Optional<OsmRouteCandidate> fetch(
            BigDecimal lat,
            BigDecimal lng,
            int requestedDistanceM,
            int seed);
}
