package com.runninggu.server.savedcourse.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** E5 geometry만으로 사용자별 멱등 저장 키를 만든다. (SPEC §6.4, 결정-33) */
public class RouteFingerprintGenerator {

    private static final long LATITUDE_LIMIT_E5 = 9_000_000L;
    private static final long LONGITUDE_LIMIT_E5 = 18_000_000L;

    public String generate(String encodedPolyline) {
        String canonicalGeometry = canonicalGeometry(encodedPolyline);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalGeometry.getBytes(StandardCharsets.UTF_8));
            return "v1:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    String canonicalGeometry(String encodedPolyline) {
        if (encodedPolyline == null || encodedPolyline.isBlank()) {
            throw invalid("pathPolyline은 비어 있을 수 없습니다.");
        }

        List<E5Point> points = decode(encodedPolyline);
        StringBuilder canonical = new StringBuilder(points.size() * 22);
        E5Point previous = null;
        for (E5Point point : points) {
            if (point.equals(previous)) {
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append(';');
            }
            appendE5(canonical, point.latE5());
            canonical.append(',');
            appendE5(canonical, point.lngE5());
            previous = point;
        }
        return canonical.toString();
    }

    private List<E5Point> decode(String encodedPolyline) {
        List<E5Point> points = new ArrayList<>();
        int[] index = {0};
        long latitude = 0;
        long longitude = 0;
        while (index[0] < encodedPolyline.length()) {
            latitude = addExact(latitude, decodeDelta(encodedPolyline, index));
            if (index[0] >= encodedPolyline.length()) {
                throw invalid("pathPolyline의 경도 값이 누락됐습니다.");
            }
            longitude = addExact(longitude, decodeDelta(encodedPolyline, index));
            if (latitude < -LATITUDE_LIMIT_E5
                    || latitude > LATITUDE_LIMIT_E5
                    || longitude < -LONGITUDE_LIMIT_E5
                    || longitude > LONGITUDE_LIMIT_E5) {
                throw invalid("pathPolyline 좌표가 WGS84 범위를 벗어났습니다.");
            }
            points.add(new E5Point(latitude, longitude));
        }
        if (points.isEmpty()) {
            throw invalid("pathPolyline에 좌표가 없습니다.");
        }
        return points;
    }

    private long decodeDelta(String encodedPolyline, int[] index) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (index[0] >= encodedPolyline.length()) {
                throw invalid("pathPolyline이 완전한 E5 형식이 아닙니다.");
            }
            int value = encodedPolyline.charAt(index[0]++) - 63;
            if (value < 0 || value > 63) {
                throw invalid("pathPolyline에 허용되지 않는 문자가 있습니다.");
            }
            if (shift > 60) {
                throw invalid("pathPolyline 좌표 증분이 너무 큽니다.");
            }
            result |= (long) (value & 0x1f) << shift;
            if ((value & 0x20) == 0) {
                break;
            }
            shift += 5;
        }
        return (result & 1L) == 0 ? result >>> 1 : ~(result >>> 1);
    }

    private long addExact(long coordinate, long delta) {
        try {
            return Math.addExact(coordinate, delta);
        } catch (ArithmeticException exception) {
            throw invalid("pathPolyline 좌표 누적값이 너무 큽니다.");
        }
    }

    private void appendE5(StringBuilder output, long value) {
        if (value < 0) {
            output.append('-');
        }
        long absolute = Math.abs(value);
        output.append(absolute / 100_000L).append('.');
        long fraction = absolute % 100_000L;
        if (fraction < 10_000L) output.append('0');
        if (fraction < 1_000L) output.append('0');
        if (fraction < 100L) output.append('0');
        if (fraction < 10L) output.append('0');
        output.append(fraction);
    }

    private InvalidCoursePolylineException invalid(String message) {
        return new InvalidCoursePolylineException(message);
    }

    private record E5Point(long latE5, long lngE5) {}
}
