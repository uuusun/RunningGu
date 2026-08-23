package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryViews.Block;

public record ItineraryBlockCreatedResponse(long blockId, int orderNo) {

    public static ItineraryBlockCreatedResponse from(Block block) {
        return new ItineraryBlockCreatedResponse(block.id(), block.orderNo());
    }
}
