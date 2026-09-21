package com.runninggu.server.itinerary.domain;

/** 자동 식사 블록에서만 사용하는 서버 내부 후보 등급이다. (SPEC §5.6 · 결정-75) */
public enum MealSuitability {
    PREFERRED,
    SECONDARY,
    EXCLUDED,
    UNKNOWN
}
