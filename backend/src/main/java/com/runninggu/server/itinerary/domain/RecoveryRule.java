package com.runninggu.server.itinerary.domain;

/** 종목별 회복 규칙 값은 포팅 시 변경하지 않는다. (SPEC §5.1) */
public record RecoveryRule(
        boolean noHard,
        String intensity,
        String dday,
        String dplus) {}
