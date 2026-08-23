package com.runninggu.server.itinerary.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** 생성 요청에 포함된 선택 숙소 snapshot이다. (API 명세 §5-1) */
public record ItineraryHotel(String name, BigDecimal lat, BigDecimal lng) {

    public ItineraryHotel {
        name = Objects.requireNonNull(name);
        lat = Objects.requireNonNull(lat);
        lng = Objects.requireNonNull(lng);
    }
}
