package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryViews.PageResult;
import com.runninggu.server.itinerary.application.ItineraryViews.Recovery;
import com.runninggu.server.itinerary.application.ItineraryViews.Summary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ItineraryListResponse(List<Item> content, Page page) {

    public static ItineraryListResponse from(PageResult result) {
        return new ItineraryListResponse(
                result.content().stream().map(Item::from).toList(),
                new Page(
                        result.number(),
                        result.size(),
                        result.totalElements(),
                        result.hasNext()));
    }

    public record Item(
            long id,
            String title,
            long contestId,
            String contestName,
            String event,
            String region,
            RecoveryResponse recovery,
            LocalDate startDate,
            LocalDate endDate,
            int placeCount,
            Instant createdAt,
            boolean active,
            boolean needsRegeneration) {

        private static Item from(Summary summary) {
            return new Item(
                    summary.id(),
                    summary.title(),
                    summary.contestId(),
                    summary.contestName(),
                    summary.event(),
                    summary.region(),
                    RecoveryResponse.from(summary.recovery()),
                    summary.startDate(),
                    summary.endDate(),
                    summary.placeCount(),
                    summary.createdAt(),
                    summary.active(),
                    summary.needsRegeneration());
        }
    }

    public record RecoveryResponse(String label, String note) {

        static RecoveryResponse from(Recovery recovery) {
            return recovery == null ? null : new RecoveryResponse(recovery.label(), recovery.note());
        }
    }

    public record Page(int number, int size, long totalElements, boolean hasNext) {}
}
