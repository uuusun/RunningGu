package com.runninggu.server.festival.domain;

import java.time.LocalDate;

/** 대회장과의 거리를 계산해 공개 API에 노출할 인근 축제다. (API 명세 §3-5) */
public record NearbyFestival(
        String contentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        double distanceKm,
        String imageUrl,
        String address) {}
