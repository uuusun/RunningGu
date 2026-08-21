package com.runninggu.server.festival.api;

import com.runninggu.server.festival.domain.HomeFestival;
import java.util.List;

public record HomeFestivalListResponse(List<HomeFestivalResponse> items) {

    public static HomeFestivalListResponse from(List<HomeFestival> festivals) {
        return new HomeFestivalListResponse(
                festivals.stream().map(HomeFestivalResponse::from).toList());
    }
}
