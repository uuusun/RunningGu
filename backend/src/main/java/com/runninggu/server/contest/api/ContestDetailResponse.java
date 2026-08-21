package com.runninggu.server.contest.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.runninggu.server.contest.application.ContestDetailItem;
import com.runninggu.server.contest.application.ContestListItem;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatus;
import com.runninggu.server.contest.domain.ContestSourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ContestDetailResponse(
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
        boolean favorite,
        String organizer,
        String officialUrl,
        BigDecimal lat,
        BigDecimal lng,
        int dDay) {

    public static ContestDetailResponse from(ContestDetailItem item) {
        ContestListItem contest = item.contest();
        return new ContestDetailResponse(
                contest.id(),
                contest.active(),
                contest.name(),
                contest.region(),
                contest.place(),
                contest.contestDate(),
                contest.startTime(),
                contest.events(),
                contest.registrationStatus(),
                contest.applyStart(),
                contest.applyEnd(),
                contest.imageUrl(),
                contest.sources(),
                contest.checkedAt(),
                contest.favorite(),
                item.organizer(),
                item.officialUrl(),
                item.lat(),
                item.lng(),
                item.dDay());
    }
}
