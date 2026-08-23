package com.runninggu.server.itinerary.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/** 동선 생성에 필요한 canonical 대회 필드만 추린다. (SPEC §5.6, 결정-53) */
public record ItineraryRace(
        long id,
        String name,
        String place,
        String roadAddress,
        LocalDate date,
        LocalTime startTime,
        BigDecimal lat,
        BigDecimal lng) {

    public ItineraryRace {
        name = Objects.requireNonNull(name);
        place = Objects.requireNonNull(place);
        date = Objects.requireNonNull(date);
        lat = Objects.requireNonNull(lat);
        lng = Objects.requireNonNull(lng);
        roadAddress = normalizeNullable(roadAddress);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
