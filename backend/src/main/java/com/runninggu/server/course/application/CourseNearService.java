package com.runninggu.server.course.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import com.runninggu.server.course.domain.CourseSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class CourseNearService {

    public static final BigDecimal DEFAULT_TARGET_KM = BigDecimal.valueOf(5);
    public static final BigDecimal DEFAULT_RADIUS_KM = BigDecimal.valueOf(8);
    public static final int DEFAULT_SIZE = 12;
    public static final int MAX_SIZE = 12;

    private static final String KAKAO_ATTRIBUTION = "카카오 로컬";
    private static final String OSM_ATTRIBUTION = "© OpenStreetMap contributors";

    private final CourseCatalog catalog;
    private final CuratedCourseRouteBuilder routeBuilder;
    private final OsmRouteGenerator osmRouteGenerator;
    private final WalkingSpotService walkingSpotService;

    public CourseNearService(
            CourseCatalog catalog,
            CuratedCourseRouteBuilder routeBuilder,
            OsmRouteGenerator osmRouteGenerator,
            WalkingSpotService walkingSpotService) {
        this.catalog = catalog;
        this.routeBuilder = routeBuilder;
        this.osmRouteGenerator = osmRouteGenerator;
        this.walkingSpotService = walkingSpotService;
    }

    public CourseNearResult find(
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal targetKm,
            BigDecimal radiusKm,
            int size) {
        validate(lat, lng, targetKm, radiusKm, size);
        CourseCatalogSnapshot snapshot = catalog.snapshot();
        List<CuratedCourseRoute> routes = routeBuilder.build(
                snapshot,
                lat,
                lng,
                targetKm,
                radiusKm);
        OsmRouteSearchResult osm = routes.isEmpty()
                ? osmRouteGenerator.generate(lat, lng, targetKm)
                : OsmRouteSearchResult.normal(java.util.Optional.empty());
        WalkingSpotSearchResult walking = walkingSpotService.search(lat, lng);

        List<CourseNearItem> combined = new ArrayList<>(routes);
        osm.route().ifPresent(combined::add);
        combined.addAll(walking.items());
        List<CourseNearItem> items = combined.stream()
                .sorted(Comparator.comparingInt(CourseNearItem::distanceM)
                        .thenComparingInt(this::kindOrder)
                        .thenComparing(CourseNearItem::name))
                .limit(size)
                .toList();

        List<CourseDegradedSource> degraded = new ArrayList<>();
        if (osm.degraded()) {
            degraded.add(CourseDegradedSource.OSM);
        }
        if (walking.degraded()) {
            degraded.add(CourseDegradedSource.KAKAO);
        }
        if (items.isEmpty() && !degraded.isEmpty()) {
            throw new ApiException(
                    ErrorCode.COURSE_SOURCES_UNAVAILABLE,
                    "주변 경로와 장소를 불러오지 못했습니다.");
        }
        return new CourseNearResult(items, List.copyOf(degraded), attributions(snapshot, items));
    }

    private List<String> attributions(
            CourseCatalogSnapshot snapshot,
            List<CourseNearItem> items) {
        Set<String> usedCourseSources = new LinkedHashSet<>();
        boolean usesOsm = false;
        boolean usesKakao = false;
        for (CourseNearItem item : items) {
            if (item instanceof CuratedCourseRoute route) {
                usedCourseSources.add(route.sourceKey());
            } else if (item instanceof OsmGeneratedRoute) {
                usesOsm = true;
            } else if (item instanceof WalkingSpot) {
                usesKakao = true;
            }
        }
        List<String> attributions = new ArrayList<>();
        snapshot.sources().stream()
                .filter(source -> usedCourseSources.contains(source.key()))
                .map(CourseSource::attribution)
                .forEach(attributions::add);
        if (usesOsm) {
            attributions.add(OSM_ATTRIBUTION);
        }
        if (usesKakao) {
            attributions.add(KAKAO_ATTRIBUTION);
        }
        return List.copyOf(attributions);
    }

    private int kindOrder(CourseNearItem item) {
        return item instanceof WalkingSpot ? 1 : 0;
    }

    private void validate(
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal targetKm,
            BigDecimal radiusKm,
            int size) {
        if (lat == null
                || lat.compareTo(BigDecimal.valueOf(-90)) < 0
                || lat.compareTo(BigDecimal.valueOf(90)) > 0
                || lng == null
                || lng.compareTo(BigDecimal.valueOf(-180)) < 0
                || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw validation("lat/lng는 WGS84 좌표 범위여야 합니다.");
        }
        if (targetKm == null
                || targetKm.compareTo(BigDecimal.ONE) < 0
                || targetKm.compareTo(BigDecimal.valueOf(21)) > 0
                || targetKm.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
            throw validation("targetKm는 1~21km 범위의 0.5km 단위여야 합니다.");
        }
        if (radiusKm == null
                || radiusKm.compareTo(BigDecimal.ZERO) <= 0
                || !Double.isFinite(radiusKm.doubleValue())) {
            throw validation("radiusKm는 0보다 커야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw validation("size는 1 이상 12 이하여야 합니다.");
        }
    }

    private ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
