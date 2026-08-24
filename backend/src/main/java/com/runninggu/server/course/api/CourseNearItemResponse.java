package com.runninggu.server.course.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.runninggu.server.course.application.CourseNearItem;
import com.runninggu.server.course.application.CuratedCourseRoute;
import com.runninggu.server.course.application.OsmGeneratedRoute;
import com.runninggu.server.course.application.WalkingSpot;
import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourseNearItemResponse(
        String kind,
        String name,
        int distanceM,
        BigDecimal lat,
        BigDecimal lng,
        String routeId,
        CourseDataSource dataSource,
        CourseDifficulty difficulty,
        BigDecimal routeKm,
        Integer durationMin,
        Integer gainM,
        List<Integer> elevationProfileM,
        Boolean shortfall,
        String pathPolyline,
        String sourceCourseId,
        String sido,
        String sigun,
        BigDecimal fullDistanceKm,
        String category,
        String address,
        String placeUrl) {

    public static CourseNearItemResponse from(CourseNearItem item) {
        if (item instanceof CuratedCourseRoute route) {
            return new CourseNearItemResponse(
                    "ROUTE",
                    route.name(),
                    route.distanceM(),
                    route.lat(),
                    route.lng(),
                    route.routeId(),
                    route.dataSource(),
                    route.difficulty(),
                    route.routeKm(),
                    route.durationMin(),
                    route.gainM(),
                    route.elevationProfileM(),
                    route.shortfall(),
                    route.pathPolyline(),
                    route.sourceCourseId(),
                    route.sido(),
                    route.sigun(),
                    route.fullDistanceKm(),
                    null,
                    null,
                    null);
        }
        if (item instanceof OsmGeneratedRoute route) {
            return new CourseNearItemResponse(
                    "ROUTE",
                    route.name(),
                    route.distanceM(),
                    route.lat(),
                    route.lng(),
                    route.routeId(),
                    route.dataSource(),
                    route.difficulty(),
                    route.routeKm(),
                    route.durationMin(),
                    route.gainM(),
                    route.elevationProfileM(),
                    route.shortfall(),
                    route.pathPolyline(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        WalkingSpot place = (WalkingSpot) item;
        return new CourseNearItemResponse(
                "PLACE",
                place.name(),
                place.distanceM(),
                place.lat(),
                place.lng(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                place.category(),
                place.address(),
                place.placeUrl());
    }
}
