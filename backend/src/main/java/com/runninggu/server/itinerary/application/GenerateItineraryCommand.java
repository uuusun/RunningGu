package com.runninggu.server.itinerary.application;

import java.math.BigDecimal;
import java.util.List;

/** HTTP 요청을 애플리케이션 계층으로 넘기는 입력이다. 날짜·Enum은 서비스가 계약값으로 검증한다. */
public record GenerateItineraryCommand(
        long contestId,
        String startDate,
        String endDate,
        String event,
        List<String> themes,
        HotelInput hotel) {

    public GenerateItineraryCommand {
        themes = themes == null ? List.of() : List.copyOf(themes);
    }

    public record HotelInput(String name, BigDecimal lat, BigDecimal lng) {}
}
