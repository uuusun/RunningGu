package com.runninggu.server.course.application;

import com.runninggu.server.common.config.CacheConfig;
import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.math.BigDecimal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class CachedCourseLoopGeometryQuery {

    private final OsmRouteGenerator osmRouteGenerator;

    public CachedCourseLoopGeometryQuery(OsmRouteGenerator osmRouteGenerator) {
        this.osmRouteGenerator = osmRouteGenerator;
    }

    /** 정상 geometry와 정상 0건만 5분간 캐시하고 원천 실패는 캐시하지 않는다. (API 명세 §6-5) */
    @Cacheable(
            cacheNames = CacheConfig.COURSE_LOOP_GEOMETRY_CACHE,
            key = "T(com.runninggu.server.course.application.CourseLoopCacheKey)"
                    + ".from(#entryLat, #entryLng, #targetKm)",
            sync = true)
    public CourseLoopGeometryResult find(
            BigDecimal entryLat,
            BigDecimal entryLng,
            BigDecimal targetKm) {
        OsmRouteSearchResult result = osmRouteGenerator.generate(entryLat, entryLng, targetKm);
        if (result.degraded()) {
            throw new ApiException(
                    ErrorCode.COURSE_SOURCES_UNAVAILABLE,
                    "순환 경로를 만들지 못했습니다.");
        }
        return new CourseLoopGeometryResult(result.route().map(OsmRouteGeometry::from));
    }
}
