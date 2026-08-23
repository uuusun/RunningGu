package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.poi.domain.PoiCategory;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 순수 동선 엔진 입력이다. HTTP DTO와 외부 API 모델을 직접 참조하지 않는다. (SPEC §5.6) */
public record ItineraryPlan(
        ItineraryRace race,
        ItineraryHotel hotel,
        ContestEventType event,
        List<PoiCategory> themes,
        LocalDate startDate,
        LocalDate endDate) {

    public ItineraryPlan {
        race = Objects.requireNonNull(race);
        event = Objects.requireNonNull(event);
        themes = List.copyOf(themes);
        startDate = Objects.requireNonNull(startDate);
        endDate = Objects.requireNonNull(endDate);
    }
}
