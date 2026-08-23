package com.runninggu.server.itinerary.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ItineraryViews {

    private ItineraryViews() {}

    public record Saved(long id, boolean replaced) {}

    public record PageResult(
            List<Summary> content,
            int number,
            int size,
            long totalElements,
            boolean hasNext) {}

    public record Summary(
            long id,
            String title,
            long contestId,
            String contestName,
            String event,
            String region,
            Recovery recovery,
            LocalDate startDate,
            LocalDate endDate,
            int placeCount,
            Instant createdAt,
            boolean active,
            boolean needsRegeneration) {}

    public record Details(
            long id,
            String title,
            long contestId,
            String event,
            List<String> themes,
            LocalDate startDate,
            LocalDate endDate,
            Hotel hotel,
            Recovery recovery,
            String region,
            List<Day> days,
            boolean needsRegeneration,
            CurrentContest contest) {}

    public record Hotel(String name, BigDecimal lat, BigDecimal lng) {}

    public record Recovery(String label, String note) {}

    public record Day(
            long id,
            int dayIndex,
            LocalDate date,
            String dayLabel,
            boolean recovery,
            String note,
            List<Block> blocks) {}

    public record Block(
            long id,
            int orderNo,
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

    public record CurrentContest(
            String name,
            String region,
            String place,
            LocalDate contestDate,
            String startTime,
            BigDecimal lat,
            BigDecimal lng,
            boolean active) {}

    public record Reordered(long dayId, List<Block> blocks) {}
}
