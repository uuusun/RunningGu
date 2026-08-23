package com.runninggu.server.itinerary.api;

import com.runninggu.server.itinerary.application.ItineraryViews.Reordered;
import java.util.List;

public record ItineraryReorderResponse(long dayId, List<ItineraryBlockResponse> blocks) {

    public static ItineraryReorderResponse from(Reordered reordered) {
        return new ItineraryReorderResponse(
                reordered.dayId(),
                reordered.blocks().stream().map(ItineraryBlockResponse::from).toList());
    }
}
