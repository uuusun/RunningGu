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
        String address,
        /** 공식 페이지. KTO 에 등록되지 않았거나 조회에 실패하면 null 이다. */
        String officialUrl) {

    public NearbyFestival withOfficialUrl(String officialUrl) {
        return new NearbyFestival(
                contentId, name, startDate, endDate, distanceKm, imageUrl, address, officialUrl);
    }
}
