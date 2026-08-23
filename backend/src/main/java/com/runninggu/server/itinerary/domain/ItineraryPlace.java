package com.runninggu.server.itinerary.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** 외부 POI·숙소·대회장을 같은 생성 입력으로 다루는 내부 장소 모델이다. */
public record ItineraryPlace(
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String description) {

    public ItineraryPlace {
        name = Objects.requireNonNull(name);
        lat = Objects.requireNonNull(lat);
        lng = Objects.requireNonNull(lng);
        address = normalizeNullable(address);
        description = Objects.requireNonNullElse(description, "");
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
