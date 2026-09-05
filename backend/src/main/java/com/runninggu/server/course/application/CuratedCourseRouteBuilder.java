package com.runninggu.server.course.application;

import com.runninggu.server.course.domain.Course;
import com.runninggu.server.course.domain.CourseDifficulty;
import com.runninggu.server.course.domain.CourseElevationProfile;
import com.runninggu.server.course.domain.CoursePoint;
import com.runninggu.server.course.domain.E5PolylineEncoder;
import com.runninggu.server.course.domain.GeoDistance;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** 최근접 진입점에서 목표거리 절반을 갔다가 되돌아오는 큐레이션 경로를 만든다. (SPEC §5.8) */
@Component
public class CuratedCourseRouteBuilder {

    private static final double HARD_GAIN_PER_KM = 50.0;
    private static final double EASY_GAIN_PER_KM = 15.0;

    private final E5PolylineEncoder polylineEncoder = new E5PolylineEncoder();

    public List<CuratedCourseRoute> build(
            CourseCatalogSnapshot snapshot,
            BigDecimal startLat,
            BigDecimal startLng,
            BigDecimal targetKm,
            BigDecimal radiusKm) {
        double halfTargetM = targetKm.doubleValue() * 500.0;
        double radiusM = radiusKm.doubleValue() * 1_000.0;
        List<CuratedCourseRoute> routes = new ArrayList<>();
        for (Course course : snapshot.courses()) {
            buildCourse(course, startLat, startLng, targetKm, halfTargetM, radiusM)
                    .ifPresent(routes::add);
        }
        return routes.stream()
                .sorted(Comparator.comparingInt(CuratedCourseRoute::distanceM)
                        .thenComparing(CuratedCourseRoute::routeId))
                .toList();
    }

    private java.util.Optional<CuratedCourseRoute> buildCourse(
            Course course,
            BigDecimal startLat,
            BigDecimal startLng,
            BigDecimal targetKm,
            double halfTargetM,
            double radiusM) {
        List<CoursePoint> points = course.points();
        if (points.size() < 2) {
            return java.util.Optional.empty();
        }

        Nearest nearest = nearest(points, startLat, startLng);
        if (nearest.distanceM() > radiusM) {
            return java.util.Optional.empty();
        }
        double[] cumulativeDistance = cumulativeDistance(points);
        Segment forward = oneWay(points, cumulativeDistance, nearest.index(), halfTargetM, 1);
        Segment backward = oneWay(points, cumulativeDistance, nearest.index(), halfTargetM, -1);
        Segment picked = forward.distanceM() >= backward.distanceM() ? forward : backward;
        if (picked.distanceM() <= 0 || picked.points().size() < 2) {
            return java.util.Optional.empty();
        }

        int low = Math.min(nearest.index(), picked.endIndex());
        int high = Math.max(nearest.index(), picked.endIndex());
        BigDecimal forwardGain = points.get(high).cumulativeGainM()
                .subtract(points.get(low).cumulativeGainM());
        BigDecimal netElevation = points.get(high).elevationM()
                .subtract(points.get(low).elevationM());
        BigDecimal roundTripGain = forwardGain.multiply(BigDecimal.TWO)
                .subtract(netElevation)
                .max(BigDecimal.ZERO);

        double rawRouteKm = picked.distanceM() * 2.0 / 1_000.0;
        double gainPerKm = roundTripGain.doubleValue() / rawRouteKm;
        if (!Double.isFinite(gainPerKm) || gainPerKm >= HARD_GAIN_PER_KM) {
            return java.util.Optional.empty();
        }

        List<CoursePoint> routePoints = roundTrip(picked.points());
        BigDecimal routeKm = BigDecimal.valueOf(rawRouteKm)
                .setScale(2, RoundingMode.HALF_UP);
        int gainM = roundTripGain.setScale(0, RoundingMode.HALF_UP).intValueExact();
        CourseDifficulty difficulty = gainPerKm < EASY_GAIN_PER_KM
                ? CourseDifficulty.EASY
                : CourseDifficulty.NORMAL;
        int durationMin = Math.max(
                1,
                routeKm.multiply(BigDecimal.valueOf(1_000))
                        .divide(BigDecimal.valueOf(110), 0, RoundingMode.HALF_UP)
                        .intValueExact());
        CoursePoint entry = points.get(nearest.index());
        return java.util.Optional.of(new CuratedCourseRoute(
                "curated:" + course.courseId(),
                course.source(),
                course.dataSource(),
                course.courseName() + " 왕복",
                Math.max(0, Math.toIntExact(Math.round(nearest.distanceM()))),
                entry.lat(),
                entry.lng(),
                difficulty,
                routeKm,
                durationMin,
                gainM,
                CourseElevationProfile.sample(routePoints),
                picked.distanceM() * 2.0 < targetKm.doubleValue() * 1_000.0 - 300.0,
                polylineEncoder.encode(routePoints),
                course.courseId(),
                course.sido(),
                course.sigun(),
                course.distanceKm()));
    }

    private Nearest nearest(
            List<CoursePoint> points,
            BigDecimal startLat,
            BigDecimal startLng) {
        int bestIndex = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < points.size(); index++) {
            CoursePoint point = points.get(index);
            double distance = GeoDistance.meters(
                    startLat,
                    startLng,
                    point.lat(),
                    point.lng());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return new Nearest(bestIndex, bestDistance);
    }

    private double[] cumulativeDistance(List<CoursePoint> points) {
        double[] cumulative = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            CoursePoint previous = points.get(index - 1);
            CoursePoint current = points.get(index);
            cumulative[index] = cumulative[index - 1] + GeoDistance.meters(
                    previous.lat(),
                    previous.lng(),
                    current.lat(),
                    current.lng());
        }
        return cumulative;
    }

    private Segment oneWay(
            List<CoursePoint> points,
            double[] cumulative,
            int start,
            double targetM,
            int direction) {
        int end = start;
        if (direction > 0) {
            while (end < points.size() - 1
                    && cumulative[end] - cumulative[start] < targetM) {
                end++;
            }
            return new Segment(
                    List.copyOf(points.subList(start, end + 1)),
                    cumulative[end] - cumulative[start],
                    end);
        }
        while (end > 0 && cumulative[start] - cumulative[end] < targetM) {
            end--;
        }
        List<CoursePoint> reversed = new ArrayList<>(points.subList(end, start + 1));
        java.util.Collections.reverse(reversed);
        return new Segment(
                List.copyOf(reversed),
                cumulative[start] - cumulative[end],
                end);
    }

    private List<CoursePoint> roundTrip(List<CoursePoint> outbound) {
        List<CoursePoint> route = new ArrayList<>(outbound);
        for (int index = outbound.size() - 2; index >= 0; index--) {
            route.add(outbound.get(index));
        }
        return List.copyOf(route);
    }

    private record Nearest(int index, double distanceM) {}

    private record Segment(
            List<CoursePoint> points,
            double distanceM,
            int endIndex) {}
}
