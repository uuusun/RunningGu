package com.runninggu.server.itinerary.domain;

import com.runninggu.server.contest.domain.Contest;
import java.math.BigDecimal;
import java.time.LocalTime;

public record ItineraryBlockSnapshot(
        Contest contest,
        BlockType blockType,
        int orderNo,
        LocalTime startTime,
        String title,
        BlockCategory category,
        String placeName,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String description) {

    public boolean systemManaged() {
        return blockType == BlockType.RACE;
    }
}
