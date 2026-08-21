package com.runninggu.server.contest.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.runninggu.server.contest.application.ContestClosingSoonItem;
import com.runninggu.server.contest.application.ContestListItem;
import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.contest.domain.ContestRegistrationStatus;
import com.runninggu.server.contest.domain.ContestSourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record ContestClosingSoonResponse(List<Item> items) {

    public static ContestClosingSoonResponse from(List<ContestClosingSoonItem> items) {
        return new ContestClosingSoonResponse(items.stream().map(Item::from).toList());
    }

    public record Item(
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
            int dDayApply) {

        private static Item from(ContestClosingSoonItem item) {
            ContestListItem contest = item.contest();
            return new Item(
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
                    item.dDayApply());
        }
    }
}
