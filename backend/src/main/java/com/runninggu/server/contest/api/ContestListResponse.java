package com.runninggu.server.contest.api;

import com.runninggu.server.contest.application.ContestListResult;
import java.util.List;

public record ContestListResponse(
        List<ContestCardResponse> items,
        String nextCursor,
        boolean hasNext) {

    public static ContestListResponse from(ContestListResult result) {
        return new ContestListResponse(
                result.items().stream().map(ContestCardResponse::from).toList(),
                result.nextCursor(),
                result.hasNext());
    }
}
