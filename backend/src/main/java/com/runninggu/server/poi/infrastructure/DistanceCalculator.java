package com.runninggu.server.poi.infrastructure;

import java.math.BigDecimal;

final class DistanceCalculator {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private DistanceCalculator() {}

    static int meters(
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
        double distance = EARTH_RADIUS_M
                * 2
                * Math.atan2(Math.sqrt(clamped), Math.sqrt(1 - clamped));
        return Math.max(0, Math.toIntExact(Math.round(distance)));
    }
}
