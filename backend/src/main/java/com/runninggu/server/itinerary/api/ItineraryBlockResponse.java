package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryViews.Block;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record ItineraryBlockResponse(
        long id,
        int orderNo,
        @Schema(pattern = "HH:mm") String startTime,
        String title,
        String category,
        String placeName,
        String address,
        BigDecimal lat,
        BigDecimal lng,
        String description,
        @Schema(allowableValues = {"USER", "RACE"}) String blockType,
        boolean systemManaged) {

    public static ItineraryBlockResponse from(Block block) {
        return new ItineraryBlockResponse(
                block.id(),
                block.orderNo(),
                block.startTime(),
                block.title(),
                block.category(),
                block.placeName(),
                block.address(),
                block.lat(),
                block.lng(),
                block.description(),
                block.blockType(),
                block.systemManaged());
    }
}
