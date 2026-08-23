package com.runninggu.server.savedcourse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteFingerprintGeneratorTest {

    private final RouteFingerprintGenerator generator = new RouteFingerprintGenerator();

    @Test
    void Google_E5_표준_좌표를_소수점_다섯자리로_직렬화한다() {
        String polyline = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";

        assertThat(generator.canonicalGeometry(polyline))
                .isEqualTo("38.50000,-120.20000;40.70000,-120.95000;43.25200,-126.45300");
        assertThat(generator.generate(polyline))
                .matches("v1:[0-9a-f]{64}");
    }

    @Test
    void 연속_중복점은_제거하지만_진행_반대와_E5_한단위는_구분한다() {
        long[][] forward = {{3_712_345, 12_712_345}, {3_712_346, 12_712_346}};
        long[][] duplicate = {
            {3_712_345, 12_712_345},
            {3_712_345, 12_712_345},
            {3_712_346, 12_712_346}
        };
        long[][] reverse = {{3_712_346, 12_712_346}, {3_712_345, 12_712_345}};
        long[][] oneUnitDifferent = {{3_712_345, 12_712_345}, {3_712_347, 12_712_346}};

        assertThat(generator.generate(encode(duplicate)))
                .isEqualTo(generator.generate(encode(forward)));
        assertThat(generator.generate(encode(reverse)))
                .isNotEqualTo(generator.generate(encode(forward)));
        assertThat(generator.generate(encode(oneUnitDifferent)))
                .isNotEqualTo(generator.generate(encode(forward)));
    }

    @Test
    void 잘리거나_범위를_벗어난_polyline을_거부한다() {
        assertThatThrownBy(() -> generator.generate("_"))
                .isInstanceOf(InvalidCoursePolylineException.class);
        assertThatThrownBy(() -> generator.generate(" !"))
                .isInstanceOf(InvalidCoursePolylineException.class);
        assertThatThrownBy(() -> generator.generate(encode(new long[][] {{9_000_001, 0}})))
                .isInstanceOf(InvalidCoursePolylineException.class);
    }

    private String encode(long[][] points) {
        StringBuilder encoded = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;
        for (long[] point : points) {
            appendDelta(encoded, point[0] - previousLat);
            appendDelta(encoded, point[1] - previousLng);
            previousLat = point[0];
            previousLng = point[1];
        }
        return encoded.toString();
    }

    private void appendDelta(StringBuilder encoded, long delta) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        List<Integer> chunks = new ArrayList<>();
        while (value >= 0x20) {
            chunks.add((int) ((value & 0x1f) | 0x20));
            value >>= 5;
        }
        chunks.add((int) value);
        chunks.forEach(chunk -> encoded.append((char) (chunk + 63)));
    }
}
