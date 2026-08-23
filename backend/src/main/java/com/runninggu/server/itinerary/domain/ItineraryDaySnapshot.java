package com.runninggu.server.itinerary.domain;

import java.time.LocalDate;
import java.util.List;

public record ItineraryDaySnapshot(
        int dayIndex,
        LocalDate date,
        boolean recovery,
        String note,
        List<ItineraryBlockSnapshot> blocks) {

    public ItineraryDaySnapshot {
        blocks = List.copyOf(blocks);
    }
}
