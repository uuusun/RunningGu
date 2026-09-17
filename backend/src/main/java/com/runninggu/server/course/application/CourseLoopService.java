package com.runninggu.server.course.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 걷기 스팟을 진입점으로 OSM 순환 경로 한 건을 만든다. (SPEC 결정-68 · API 명세 §6-5) */
@Service
public class CourseLoopService {

    private static final String OSM_ATTRIBUTION = "© OpenStreetMap contributors";

    private final CachedCourseLoopGeometryQuery geometryQuery;

    public CourseLoopService(CachedCourseLoopGeometryQuery geometryQuery) {
        this.geometryQuery = geometryQuery;
    }

    public CourseLoopResult find(
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal entryLat,
            BigDecimal entryLng,
            BigDecimal targetKm,
            String entryName) {
        CourseRequestValidator.validateCoordinates(lat, lng, "lat/lng");
        CourseRequestValidator.validateCoordinates(entryLat, entryLng, "entryLat/entryLng");
        CourseRequestValidator.validateTargetKm(targetKm);

        Optional<OsmRouteGeometry> geometry = geometryQuery
                .find(entryLat, entryLng, targetKm)
                .geometry();
        if (geometry.isEmpty()) {
            return new CourseLoopResult(Optional.empty(), List.of());
        }
        OsmGeneratedRoute route = OsmGeneratedRouteFactory.create(
                geometry.orElseThrow(),
                lat,
                lng,
                targetKm,
                entryName);
        return new CourseLoopResult(Optional.of(route), List.of(OSM_ATTRIBUTION));
    }
}
