package com.runninggu.server.itinerary.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PersistItineraryCommand(
        long contestId,
        String event,
        List<String> themes,
        LocalDate startDate,
        LocalDate endDate,
        HotelInput hotel,
        RecoveryInput recovery,
        List<DayInput> days) {

    public record HotelInput(String name, BigDecimal lat, BigDecimal lng) {}

    public record RecoveryInput(String label, String note) {}

    public record DayInput(
            int dayIndex,
            LocalDate date,
            boolean recovery,
            String note,
            List<BlockInput> blocks) {}

    public record BlockInput(
            String startTime,
            String title,
            String category,
            String placeName,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            String description,
            String blockType,
            boolean systemManaged) {}
}
