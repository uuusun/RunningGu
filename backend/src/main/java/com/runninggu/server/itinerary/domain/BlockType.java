package com.runninggu.server.itinerary.domain;

/** RACE 블록은 시스템이 관리하고 USER 블록만 편집할 수 있다. (SPEC §5.6~5.7) */
public enum BlockType {
    USER,
    RACE
}
