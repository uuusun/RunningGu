package com.runninggu.server.course.application;

import java.math.BigDecimal;
import java.util.Objects;

public record OsmRouteCoordinate(
        BigDecimal lat,
        BigDecimal lng,
        BigDecimal elevationM) {

    public OsmRouteCoordinate {
        Objects.requireNonNull(lat);
        Objects.requireNonNull(lng);
        Objects.requireNonNull(elevationM);
    }
}
