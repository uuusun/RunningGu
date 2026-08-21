package com.runninggu.server.contest.domain;

import java.time.LocalDate;
import java.util.Objects;

/** 크롤 시점 값 대신 조회일의 KST 날짜로 접수 상태를 파생한다. (SPEC §5.5) */
public final class ContestRegistrationStatusPolicy {

    private ContestRegistrationStatusPolicy() {}

    public static ContestRegistrationStatus derive(
            LocalDate applyStart,
            LocalDate applyEnd,
            ContestRegistrationStatus sourceStatus,
            LocalDate today) {
        Objects.requireNonNull(today);

        if (applyEnd != null && applyEnd.isBefore(today)) {
            return ContestRegistrationStatus.CLOSED;
        }
        if (applyStart != null && today.isBefore(applyStart)) {
            return ContestRegistrationStatus.BEFORE;
        }
        if (applyStart != null) {
            return ContestRegistrationStatus.OPEN;
        }
        return sourceStatus == null ? ContestRegistrationStatus.UNKNOWN : sourceStatus;
    }
}
