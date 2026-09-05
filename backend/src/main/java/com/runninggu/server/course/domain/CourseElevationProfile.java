package com.runninggu.server.course.domain;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 순서를 보존해 고도를 정수 미터·최대 100개로 균등 축약한다. (SPEC §5.8·API 명세 §6-4) */
public final class CourseElevationProfile {
    private static final int MAX_SAMPLES = 100;

    private CourseElevationProfile() {}

    public static List<Integer> sample(List<CoursePoint> points) {
        int sampleCount = Math.min(points.size(), MAX_SAMPLES);
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
}
