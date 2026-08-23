package com.runninggu.server.favorite.application;

import com.runninggu.server.contest.application.ContestListItem;
import java.util.List;

public record FavoriteListResult(
        List<ContestListItem> content,
        int number,
        int size,
        long totalElements,
        boolean hasNext) {}
