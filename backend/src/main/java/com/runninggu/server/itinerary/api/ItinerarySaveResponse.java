package com.runninggu.server.itinerary.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.runninggu.server.itinerary.application.ItineraryViews.Saved;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItinerarySaveResponse(long id, Boolean replaced) {

    public static ItinerarySaveResponse from(Saved saved) {
        return new ItinerarySaveResponse(saved.id(), saved.replaced() ? true : null);
    }
}
