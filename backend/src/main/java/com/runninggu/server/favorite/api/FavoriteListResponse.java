package com.runninggu.server.favorite.api;

import com.runninggu.server.contest.api.ContestCardResponse;
import com.runninggu.server.favorite.application.FavoriteListResult;
import java.util.List;

public record FavoriteListResponse(
        List<ContestCardResponse> content,
        Page page) {

    public static FavoriteListResponse from(FavoriteListResult result) {
        return new FavoriteListResponse(
                result.content().stream().map(ContestCardResponse::from).toList(),
                new Page(
                        result.number(),
                        result.size(),
                        result.totalElements(),
                        result.hasNext()));
    }

    public record Page(int number, int size, long totalElements, boolean hasNext) {}
}
