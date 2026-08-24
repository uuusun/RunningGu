package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CoursePoint;
import com.runninggu.server.course.domain.E5PolylineEncoder;
import com.runninggu.server.course.domain.GeoDistance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 16개 seed 후보에 확정 품질 상한을 적용해 OSM 순환 경로를 고른다. (SPEC §5.8) */
@Service
public class GraphHopperOsmRouteGenerator implements OsmRouteGenerator {

    private static final double DISTANCE_CORRECTION = 0.78;
    private static final int SEED_COUNT = 16;
    private static final int MAX_ELEVATION_SAMPLES = 100;
    private static final double EASY_GAIN_PER_KM = 15.0;
    private static final double HARD_GAIN_PER_KM = 50.0;
    private static final double MAX_MAJOR_ROAD_RATIO = 0.10;
    private static final double PREFERRED_MAJOR_ROAD_RATIO = 0.05;
    private static final double MAX_TURNS_PER_KM = 6.0;

    private final OsmRoundTripSource source;
    private final E5PolylineEncoder polylineEncoder = new E5PolylineEncoder();

    public GraphHopperOsmRouteGenerator(OsmRoundTripSource source) {
        this.source = source;
    }

    @Override
    public OsmRouteSearchResult generate(
            BigDecimal lat,
            BigDecimal lng,
            BigDecimal targetKm) {
        int requestedDistanceM = BigDecimal.valueOf(1_000)
                .multiply(targetKm)
                .multiply(BigDecimal.valueOf(DISTANCE_CORRECTION))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
        List<OsmRouteCandidate> candidates = new ArrayList<>();
        boolean degraded = false;
        for (int seed = 0; seed < SEED_COUNT; seed++) {
            try {
                source.fetch(lat, lng, requestedDistanceM, seed)
                        .filter(candidate -> eligible(candidate, targetKm.doubleValue()))
                        .ifPresent(candidates::add);
            } catch (OsmRouteSourceException exception) {
                degraded = true;
                break;
            }
        }

        Optional<OsmGeneratedRoute> route = candidates.stream()
                .min(candidateOrder(targetKm.doubleValue()))
                .map(candidate -> toRoute(candidate, lat, lng, targetKm));
        return degraded
                ? OsmRouteSearchResult.degraded(route)
                : OsmRouteSearchResult.normal(route);
    }

    private boolean eligible(OsmRouteCandidate candidate, double targetKm) {
        double routeKm = candidate.distanceM() / 1_000.0;
        if (!Double.isFinite(routeKm)
                || !Double.isFinite(candidate.gainM())
                || !Double.isFinite(candidate.polylineDistanceM())
                || !Double.isFinite(candidate.majorRoadDistanceM())
                || routeKm <= 0
                || candidate.polylineDistanceM() <= 0
                || candidate.gainM() < 0
                || candidate.majorRoadDistanceM() < 0
                || candidate.turnCount() < 0
                || candidate.coordinates().size() < 2) {
            return false;
        }
        double gainPerKm = candidate.gainM() / routeKm;
        double roadRatio = candidate.majorRoadDistanceM() / candidate.polylineDistanceM();
        double turnsPerKm = candidate.turnCount() / routeKm;
        return routeKm >= targetKm * 0.75
                && routeKm <= targetKm * 1.25
                && gainPerKm < HARD_GAIN_PER_KM
                && roadRatio <= MAX_MAJOR_ROAD_RATIO
                && turnsPerKm <= MAX_TURNS_PER_KM;
    }

    private Comparator<OsmRouteCandidate> candidateOrder(double targetKm) {
        return Comparator
                .comparing((OsmRouteCandidate candidate) -> roadRatio(candidate)
                        > PREFERRED_MAJOR_ROAD_RATIO)
                .thenComparingDouble(candidate -> Math.abs(candidate.distanceM() / 1_000.0 - targetKm))
                .thenComparingDouble(this::turnsPerKm)
                .thenComparingDouble(this::roadRatio)
                .thenComparingInt(OsmRouteCandidate::seed);
    }

    private double roadRatio(OsmRouteCandidate candidate) {
        return candidate.majorRoadDistanceM() / candidate.polylineDistanceM();
    }

    private double turnsPerKm(OsmRouteCandidate candidate) {
        return candidate.turnCount() / (candidate.distanceM() / 1_000.0);
    }

    private OsmGeneratedRoute toRoute(
            OsmRouteCandidate candidate,
            BigDecimal inputLat,
            BigDecimal inputLng,
            BigDecimal targetKm) {
        double rawRouteKm = candidate.distanceM() / 1_000.0;
        BigDecimal routeKm = BigDecimal.valueOf(rawRouteKm)
                .setScale(2, RoundingMode.HALF_UP);
        int gainM = Math.max(0, Math.toIntExact(Math.round(candidate.gainM())));
        CourseDifficulty difficulty = candidate.gainM() / rawRouteKm < EASY_GAIN_PER_KM
                ? CourseDifficulty.EASY
                : CourseDifficulty.NORMAL;
        List<CoursePoint> points = toCoursePoints(candidate.coordinates());
        String pathPolyline = polylineEncoder.encode(points);
        OsmRouteCoordinate entry = candidate.coordinates().getFirst();
        int accessM = Math.max(0, Math.toIntExact(Math.round(GeoDistance.meters(
                inputLat,
                inputLng,
                entry.lat(),
                entry.lng()))));
        int durationMin = Math.max(
                1,
                routeKm.multiply(BigDecimal.valueOf(1_000))
                        .divide(BigDecimal.valueOf(110), 0, RoundingMode.HALF_UP)
                        .intValueExact());
        return new OsmGeneratedRoute(
                routeId(pathPolyline),
                CourseDataSource.OSM_GENERATED,
                name(targetKm, difficulty),
                accessM,
                entry.lat(),
                entry.lng(),
                difficulty,
                routeKm,
                durationMin,
                gainM,
                elevationProfile(points),
                candidate.distanceM() < targetKm.doubleValue() * 1_000.0 - 300.0,
                pathPolyline);
    }

    private List<CoursePoint> toCoursePoints(List<OsmRouteCoordinate> coordinates) {
        List<CoursePoint> points = new ArrayList<>(coordinates.size());
        BigDecimal cumulativeGain = BigDecimal.ZERO;
        BigDecimal previousElevation = null;
        for (OsmRouteCoordinate coordinate : coordinates) {
            if (previousElevation != null) {
                cumulativeGain = cumulativeGain.add(
                        coordinate.elevationM().subtract(previousElevation).max(BigDecimal.ZERO));
            }
            points.add(new CoursePoint(
                    coordinate.lat(),
                    coordinate.lng(),
                    coordinate.elevationM(),
                    cumulativeGain));
            previousElevation = coordinate.elevationM();
        }
        return List.copyOf(points);
    }

    private List<Integer> elevationProfile(List<CoursePoint> points) {
        int sampleCount = Math.min(points.size(), MAX_ELEVATION_SAMPLES);
        List<Integer> samples = new ArrayList<>(sampleCount);
        for (int sample = 0; sample < sampleCount; sample++) {
            int index = sampleCount == 1
                    ? 0
                    : Math.toIntExact(Math.round(
                            (double) sample * (points.size() - 1) / (sampleCount - 1)));
            samples.add(points.get(index).elevationM()
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValueExact());
        }
        return List.copyOf(samples);
    }

    private String name(BigDecimal targetKm, CourseDifficulty difficulty) {
        String target = targetKm.stripTrailingZeros().toPlainString();
        String label = difficulty == CourseDifficulty.EASY ? "평지" : "완만";
        return "내 주변 " + target + "km " + label + " 러닝코스";
    }

    private String routeId(String pathPolyline) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(pathPolyline.getBytes(StandardCharsets.UTF_8));
            return "osm:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
