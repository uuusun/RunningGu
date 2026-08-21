package com.runninggu.server.contest.application;

import java.util.List;

public record ContestListResult(
        List<ContestListItem> items,
        String nextCursor,
        boolean hasNext) {}
