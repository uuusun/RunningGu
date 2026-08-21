package com.runninggu.server.contest.api;

import com.runninggu.server.contest.application.ContestDailyCount;
import java.time.LocalDate;
import java.util.List;

public record ContestDailyCountsResponse(List<Entry> counts) {

    public static ContestDailyCountsResponse from(List<ContestDailyCount> counts) {
        return new ContestDailyCountsResponse(counts.stream().map(Entry::from).toList());
    }

    public record Entry(LocalDate date, long count) {

        private static Entry from(ContestDailyCount count) {
            return new Entry(count.date(), count.count());
        }
    }
}
