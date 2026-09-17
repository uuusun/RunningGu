package com.runninggu.server.course.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 순환 경로 geometry 캐시 키다. 출발지와 이름은 응답 조립 값이므로 제외한다. (API 명세 §6-5) */
public record CourseLoopCacheKey(
        BigDecimal entryLat,
        BigDecimal entryLng,
        BigDecimal targetKm) {

    public static CourseLoopCacheKey from(
            BigDecimal entryLat,
            BigDecimal entryLng,
            BigDecimal targetKm) {
        return new CourseLoopCacheKey(
                entryLat.setScale(4, RoundingMode.HALF_UP),
                entryLng.setScale(4, RoundingMode.HALF_UP),
                targetKm.stripTrailingZeros());
    }
}
