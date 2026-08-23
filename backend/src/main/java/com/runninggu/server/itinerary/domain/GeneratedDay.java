package com.runninggu.server.itinerary.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 대회일 상대 오프셋을 유지하는 하루치 생성 결과다. (결정-53) */
public record GeneratedDay(
        int dayIndex,
        LocalDate date,
        String dayLabel,
        boolean recovery,
        String note,
        List<GeneratedBlock> blocks) {

    public GeneratedDay {
        date = Objects.requireNonNull(date);
        dayLabel = Objects.requireNonNull(dayLabel);
        note = Objects.requireNonNullElse(note, "");
        blocks = List.copyOf(blocks);
    }
}
