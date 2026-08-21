package com.runninggu.server.contest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContestRegistrationStatusPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    @ParameterizedTest
    @MethodSource("statusCases")
    void KST_오늘_기준으로_접수상태를_파생한다(
            LocalDate applyStart,
            LocalDate applyEnd,
            ContestRegistrationStatus sourceStatus,
            ContestRegistrationStatus expected) {
        assertThat(ContestRegistrationStatusPolicy.derive(
                        applyStart,
                        applyEnd,
                        sourceStatus,
                        TODAY))
                .isEqualTo(expected);
    }

    private static Stream<Arguments> statusCases() {
        return Stream.of(
                Arguments.of(
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 5, 31),
                        ContestRegistrationStatus.OPEN,
                        ContestRegistrationStatus.CLOSED),
                Arguments.of(
                        LocalDate.of(2026, 6, 2),
                        LocalDate.of(2026, 7, 1),
                        ContestRegistrationStatus.OPEN,
                        ContestRegistrationStatus.BEFORE),
                Arguments.of(
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 6, 30),
                        ContestRegistrationStatus.CLOSED,
                        ContestRegistrationStatus.OPEN),
                Arguments.of(
                        LocalDate.of(2026, 5, 1),
                        TODAY,
                        null,
                        ContestRegistrationStatus.OPEN),
                Arguments.of(
                        TODAY,
                        LocalDate.of(2026, 6, 30),
                        null,
                        ContestRegistrationStatus.OPEN),
                Arguments.of(
                        LocalDate.of(2026, 5, 1),
                        null,
                        ContestRegistrationStatus.CLOSED,
                        ContestRegistrationStatus.OPEN),
                Arguments.of(
                        LocalDate.of(2026, 6, 2),
                        null,
                        ContestRegistrationStatus.OPEN,
                        ContestRegistrationStatus.BEFORE),
                Arguments.of(
                        null,
                        LocalDate.of(2026, 5, 31),
                        ContestRegistrationStatus.OPEN,
                        ContestRegistrationStatus.CLOSED),
                Arguments.of(
                        null,
                        LocalDate.of(2026, 6, 30),
                        ContestRegistrationStatus.BEFORE,
                        ContestRegistrationStatus.BEFORE),
                Arguments.of(
                        null,
                        LocalDate.of(2026, 6, 30),
                        ContestRegistrationStatus.OPEN,
                        ContestRegistrationStatus.OPEN),
                Arguments.of(
                        null,
                        LocalDate.of(2026, 6, 30),
                        null,
                        ContestRegistrationStatus.UNKNOWN),
                Arguments.of(
                        null,
                        null,
                        ContestRegistrationStatus.BEFORE,
                        ContestRegistrationStatus.BEFORE),
                Arguments.of(
                        null,
                        null,
                        null,
                        ContestRegistrationStatus.UNKNOWN));
    }
}
