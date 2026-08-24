package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;

/** GraphHopper가 요청 시점에 생성한 OSM 순환 경로다. (SPEC §5.8) */
public record OsmGeneratedRoute(
        String routeId,
        CourseDataSource dataSource,
        String name,
        int distanceM,
        BigDecimal lat,
        BigDecimal lng,
        CourseDifficulty difficulty,
        BigDecimal routeKm,
        int durationMin,
        int gainM,
        List<Integer> elevationProfileM,
        boolean shortfall,
        String pathPolyline) implements CourseNearItem {

    public OsmGeneratedRoute {
        elevationProfileM = List.copyOf(elevationProfileM);
    }
}
