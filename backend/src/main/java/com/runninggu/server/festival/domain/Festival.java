package com.runninggu.server.festival.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 한국관광공사 축제 검색 결과를 서버 축제 기능이 함께 쓰는 값이다. (SPEC §4.4·§8.3) */
public record Festival(
        String contentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        /** KTO가 좌표를 주지 않으면 null이다. 홈 월간 축제는 좌표 없이도 노출할 수 있다. */
        BigDecimal lat,
        BigDecimal lng,
        String imageUrl,
        String address) {

    public boolean hasCoordinates() {
        return lat != null && lng != null;
    }
}
