package com.runninggu.server.itinerary.domain;

import java.time.LocalTime;
import java.util.Objects;

/** 저장 전 편집 가능한 동선 블록이다. RACE만 시스템 관리 대상이다. */
public record GeneratedBlock(
        LocalTime startTime,
        String title,
        BlockCategory category,
        ItineraryPlace place,
        String description,
        BlockType blockType) {

    public GeneratedBlock {
        startTime = Objects.requireNonNull(startTime);
        title = Objects.requireNonNull(title);
        category = Objects.requireNonNull(category);
        description = Objects.requireNonNullElse(description, "");
        blockType = Objects.requireNonNull(blockType);
    }

    public boolean systemManaged() {
        return blockType == BlockType.RACE;
    }
}
