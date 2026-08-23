package com.runninggu.server.itinerary.domain;

/** 동선 블록의 표시 분류다. (SPEC §5.3·§5.6, API 명세 부록 C) */
public enum BlockCategory {
    TOUR,
    FOOD,
    CAFE,
    WELLNESS,
    NATURE,
    HISTORY,
    LODGING,
    RACE,
    RECOVERY
}
