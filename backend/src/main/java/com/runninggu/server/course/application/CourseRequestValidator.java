package com.runninggu.server.course.application;

import com.runninggu.server.common.error.ApiException;
import com.runninggu.server.common.error.ErrorCode;
import java.math.BigDecimal;

/** 코스 조회 API가 공유하는 좌표·목표 거리 검증이다. (API 명세 §6-1·§6-5) */
public final class CourseRequestValidator {

    private CourseRequestValidator() {}

    public static void validateCoordinates(
            BigDecimal lat,
            BigDecimal lng,
            String parameterNames) {
        if (lat == null
                || lat.compareTo(BigDecimal.valueOf(-90)) < 0
                || lat.compareTo(BigDecimal.valueOf(90)) > 0
                || lng == null
                || lng.compareTo(BigDecimal.valueOf(-180)) < 0
                || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw validation(parameterNames + "는 WGS84 좌표 범위여야 합니다.");
        }
    }

    public static void validateTargetKm(BigDecimal targetKm) {
        if (targetKm == null
                || targetKm.compareTo(BigDecimal.ONE) < 0
                || targetKm.compareTo(BigDecimal.valueOf(21)) > 0
                || targetKm.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
            throw validation("targetKm는 1~21km 범위의 0.5km 단위여야 합니다.");
        }
    }

    private static ApiException validation(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }
}
