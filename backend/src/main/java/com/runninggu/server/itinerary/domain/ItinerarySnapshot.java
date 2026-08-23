package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.ContestEventType;
import com.runninggu.server.poi.domain.PoiCategory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 검증과 canonical RACE 재구성이 끝난 저장 snapshot 입력이다. */
public record ItinerarySnapshot(
        String title,
        ContestEventType event,
        List<PoiCategory> themes,
        LocalDate startDate,
        LocalDate endDate,
        String hotelName,
        BigDecimal hotelLat,
        BigDecimal hotelLng,
        String regionSnapshot,
        String recoveryLabel,
        String recoveryNote,
        List<ItineraryDaySnapshot> days) {

    public ItinerarySnapshot {
        themes = List.copyOf(themes);
        days = List.copyOf(days);
    }
}
