package com.runninggu.server.contest.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.runninggu.server.contest.application.ContestListItem;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatus;
import com.runninggu.server.contest.domain.ContestSourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ContestCardResponse(
        long id,
        boolean active,
        String name,
        String region,
        String place,
        LocalDate contestDate,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        List<ContestEventType> events,
        ContestRegistrationStatus regStatus,
        LocalDate applyStart,
        LocalDate applyEnd,
        String imageUrl,
        List<ContestSourceType> sources,
        Instant checkedAt,
        boolean favorite) {

    public static ContestCardResponse from(ContestListItem item) {
        return new ContestCardResponse(
                item.id(),
                item.active(),
                item.name(),
                item.region(),
                item.place(),
                item.contestDate(),
                item.startTime(),
                item.events(),
                item.registrationStatus(),
                item.applyStart(),
                item.applyEnd(),
                item.imageUrl(),
                item.sources(),
                item.checkedAt(),
                item.favorite());
    }
}
