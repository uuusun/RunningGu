package com.runninggu.server.festival.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 한국관광공사 축제 검색 결과 중 인근 축제 판정에 필요한 값이다. (SPEC §8.3) */
public record Festival(
        String contentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal lat,
        BigDecimal lng,
        String imageUrl,
        String address) {}
