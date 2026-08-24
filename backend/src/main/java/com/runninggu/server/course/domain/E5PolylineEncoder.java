package com.runninggu.server.course.domain;

import java.math.RoundingMode;
import java.util.List;

/** 코스 경로를 2D Google Encoded Polyline precision 5로 직렬화한다. (SPEC 결정-33) */
public final class E5PolylineEncoder {

    public String encode(List<CoursePoint> points) {
        StringBuilder encoded = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;
        for (CoursePoint point : points) {
            long lat = point.lat()
                    .movePointRight(5)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            long lng = point.lng()
                    .movePointRight(5)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            encodeDelta(lat - previousLat, encoded);
            encodeDelta(lng - previousLng, encoded);
            previousLat = lat;
            previousLng = lng;
        }
        return encoded.toString();
    }

    private void encodeDelta(long delta, StringBuilder destination) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            destination.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        destination.append((char) (value + 63));
    }
}
