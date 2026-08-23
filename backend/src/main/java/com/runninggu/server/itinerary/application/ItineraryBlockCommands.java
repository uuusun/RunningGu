package com.runninggu.server.itinerary.application;

import java.math.BigDecimal;

public final class ItineraryBlockCommands {

    private ItineraryBlockCommands() {}

    public record Add(
            String startTime,
            String title,
            String category,
            String placeName,
            String address,
            BigDecimal lat,
            BigDecimal lng,
            String description) {}

    public record FieldUpdate<T>(boolean present, T value) {

        public static <T> FieldUpdate<T> absent() {
            return new FieldUpdate<>(false, null);
        }

        public static <T> FieldUpdate<T> present(T value) {
            return new FieldUpdate<>(true, value);
        }
    }

    public record Patch(
            FieldUpdate<String> startTime,
            FieldUpdate<String> title,
            FieldUpdate<String> category,
            FieldUpdate<String> placeName,
            FieldUpdate<String> address,
            FieldUpdate<BigDecimal> lat,
            FieldUpdate<BigDecimal> lng,
            FieldUpdate<String> description) {}
}
