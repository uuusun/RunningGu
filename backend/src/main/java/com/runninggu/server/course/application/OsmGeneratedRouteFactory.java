package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.CourseDataSource;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.GeoDistance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** OSM geometry에 요청별 이름·출발지 거리를 붙인다. (SPEC §5.8 · API 명세 §6-5) */
public final class OsmGeneratedRouteFactory {

    private OsmGeneratedRouteFactory() {}

    public static OsmGeneratedRoute create(
            OsmRouteGeometry geometry,
            BigDecimal originLat,
            BigDecimal originLng,
            BigDecimal targetKm,
            String entryName) {
        int distanceM = Math.max(0, Math.toIntExact(Math.round(GeoDistance.meters(
                originLat,
                originLng,
                geometry.lat(),
                geometry.lng()))));
        int durationMin = Math.max(
                1,
                geometry.routeKm()
                        .multiply(BigDecimal.valueOf(1_000))
                        .divide(BigDecimal.valueOf(110), 0, RoundingMode.HALF_UP)
                        .intValueExact());
        boolean shortfall = geometry.routeKm()
                .compareTo(targetKm.subtract(BigDecimal.valueOf(0.3))) < 0;
        return new OsmGeneratedRoute(
                routeId(geometry.pathPolyline()),
                CourseDataSource.OSM_GENERATED,
                name(geometry, targetKm, entryName),
                distanceM,
                geometry.lat(),
                geometry.lng(),
                geometry.difficulty(),
                geometry.routeKm(),
                durationMin,
                geometry.gainM(),
                geometry.elevationProfileM(),
                shortfall,
                geometry.pathPolyline());
    }

    private static String name(
            OsmRouteGeometry geometry,
            BigDecimal targetKm,
            String entryName) {
        String label = geometry.difficulty() == CourseDifficulty.EASY ? "평지" : "완만";
        String normalizedEntryName = normalizeEntryName(entryName);
        if (normalizedEntryName == null) {
            String target = targetKm.stripTrailingZeros().toPlainString();
            return "출발지 주변 " + target + "km " + label + " 러닝코스";
        }
        String roundedRouteKm = geometry.routeKm()
                .setScale(0, RoundingMode.HALF_UP)
                .toPlainString();
        return normalizedEntryName + " 주변 " + roundedRouteKm + "km " + label + " 러닝코스";
    }

    private static String normalizeEntryName(String entryName) {
        if (entryName == null) {
            return null;
        }
        String normalized = entryName.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .collect(
                        StringBuilder::new,
                        StringBuilder::appendCodePoint,
                        StringBuilder::append)
                .toString()
                .strip();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.codePoints()
                .limit(50)
                .collect(
                        StringBuilder::new,
                        StringBuilder::appendCodePoint,
                        StringBuilder::append)
                .toString()
                .strip();
    }

    private static String routeId(String pathPolyline) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(pathPolyline.getBytes(StandardCharsets.UTF_8));
            return "osm:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
