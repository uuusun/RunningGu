package com.runninggu.server.itinerary.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** 외부 POI·숙소·대회장을 같은 생성 입력으로 다루는 내부 장소 모델이다. */
public record ItineraryPlace(
        String name,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String description,
        MealSuitability mealSuitability) {

    public ItineraryPlace(
            String name,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            String description) {
        this(name, address, lat, lng, description, MealSuitability.PREFERRED);
    }

    public ItineraryPlace {
        name = Objects.requireNonNull(name);
        lat = Objects.requireNonNull(lat);
        lng = Objects.requireNonNull(lng);
        address = normalizeNullable(address);
        description = Objects.requireNonNullElse(description, "");
        mealSuitability = Objects.requireNonNull(mealSuitability);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
