package com.runninggu.server.festival.api;

import com.runninggu.server.festival.domain.NearbyFestival;
import java.util.List;

public record NearbyFestivalListResponse(List<NearbyFestivalResponse> items) {

    public static NearbyFestivalListResponse from(List<NearbyFestival> festivals) {
        return new NearbyFestivalListResponse(festivals.stream()
                .map(NearbyFestivalResponse::from)
                .toList());
    }
}
