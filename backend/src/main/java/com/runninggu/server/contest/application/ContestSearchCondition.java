package com.runninggu.server.contest.application;

import com.runninggu.server.contest.domain.ContestEventType;
import java.time.LocalDate;
import java.util.Set;

public record ContestSearchCondition(
        String query,
        Set<ContestEventType> events,
        boolean openOnly,
        Set<String> regions,
        LocalDate date) {

    public ContestSearchCondition {
        events = events == null ? Set.of() : Set.copyOf(events);
        regions = regions == null ? Set.of() : Set.copyOf(regions);
    }
}
