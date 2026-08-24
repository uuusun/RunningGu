package com.runninggu.server.course.domain;

import java.math.BigDecimal;

public final class GeoDistance {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private GeoDistance() {}

    public static double meters(
            BigDecimal fromLat,
            BigDecimal fromLng,
            BigDecimal toLat,
            BigDecimal toLng) {
        double lat1 = Math.toRadians(fromLat.doubleValue());
        double lat2 = Math.toRadians(toLat.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(toLng.doubleValue() - fromLng.doubleValue());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1)
                        * Math.cos(lat2)
                        * Math.sin(deltaLng / 2)
                        * Math.sin(deltaLng / 2);
        double clamped = Math.max(0, Math.min(1, a));
        return EARTH_RADIUS_M
                * 2
                * Math.atan2(Math.sqrt(clamped), Math.sqrt(1 - clamped));
    }
}
