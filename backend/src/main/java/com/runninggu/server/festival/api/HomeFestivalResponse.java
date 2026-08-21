package com.runninggu.server.festival.api;

import com.runninggu.server.festival.domain.HomeFestival;
import java.time.LocalDate;

public record HomeFestivalResponse(
        String contentId,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        String region,
        String imageUrl,
        boolean inProgress) {

    public static HomeFestivalResponse from(HomeFestival festival) {
        return new HomeFestivalResponse(
                festival.contentId(),
                festival.name(),
                festival.startDate(),
                festival.endDate(),
                festival.region(),
                festival.imageUrl(),
                festival.inProgress());
    }
}
