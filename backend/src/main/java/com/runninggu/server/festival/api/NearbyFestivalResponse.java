package com.runninggu.server.festival.api;

import com.runninggu.server.festival.domain.NearbyFestival;
import java.time.LocalDate;

public record NearbyFestivalResponse(
        String contentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        double distanceKm,
        String imageUrl,
        String address) {

    public static NearbyFestivalResponse from(NearbyFestival festival) {
        return new NearbyFestivalResponse(
                festival.contentId(),
                festival.name(),
                festival.startDate(),
                festival.endDate(),
                festival.distanceKm(),
                festival.imageUrl(),
                festival.address());
    }
}
