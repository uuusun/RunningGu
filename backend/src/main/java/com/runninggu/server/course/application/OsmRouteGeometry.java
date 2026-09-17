package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 이름·출발지 거리를 제외하고 5분간 재사용할 OSM 경로 모양이다. (API 명세 §6-5) */
public record OsmRouteGeometry(
        BigDecimal lat,
        BigDecimal lng,
        CourseDifficulty difficulty,
        BigDecimal routeKm,
        int gainM,
        List<Integer> elevationProfileM,
        String pathPolyline) {

    public OsmRouteGeometry {
        Objects.requireNonNull(lat);
        Objects.requireNonNull(lng);
        Objects.requireNonNull(difficulty);
        Objects.requireNonNull(routeKm);
        elevationProfileM = List.copyOf(elevationProfileM);
        Objects.requireNonNull(pathPolyline);
    }

    public static OsmRouteGeometry from(OsmGeneratedRoute route) {
        return new OsmRouteGeometry(
                route.lat(),
                route.lng(),
                route.difficulty(),
                route.routeKm(),
                route.gainM(),
                route.elevationProfileM(),
                route.pathPolyline());
    }
}
