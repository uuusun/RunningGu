package com.runninggu.server.contest.application;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatus;
import com.runninggu.server.contest.domain.ContestSourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ContestListItem(
        long id,
        boolean active,
        String name,
        String region,
        String place,
        LocalDate contestDate,
        LocalTime startTime,
        List<ContestEventType> events,
        ContestRegistrationStatus registrationStatus,
        LocalDate applyStart,
        LocalDate applyEnd,
        String imageUrl,
        List<ContestSourceType> sources,
        Instant checkedAt,
        boolean favorite) {}
