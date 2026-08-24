package com.runninggu.server.course.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** 축약 좌표와 원본 해상도에서 계산한 누적 상승고도를 함께 보존한다. (SPEC §5.8) */
public record CoursePoint(
        BigDecimal lat,
        BigDecimal lng,
        BigDecimal elevationM,
        BigDecimal cumulativeGainM) {

    public CoursePoint {
        Objects.requireNonNull(lat);
        Objects.requireNonNull(lng);
        Objects.requireNonNull(elevationM);
        Objects.requireNonNull(cumulativeGainM);
    }
}
