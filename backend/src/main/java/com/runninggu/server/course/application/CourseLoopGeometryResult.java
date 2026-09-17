package com.runninggu.server.course.application;

import java.util.Optional;

/** 정상 0건도 null 없이 캐시하기 위한 순환 경로 geometry 조회 결과다. */
public record CourseLoopGeometryResult(Optional<OsmRouteGeometry> geometry) {

    public CourseLoopGeometryResult {
        geometry = geometry == null ? Optional.empty() : geometry;
    }
}
