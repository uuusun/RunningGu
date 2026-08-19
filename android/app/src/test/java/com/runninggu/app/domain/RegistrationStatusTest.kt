package com.runninggu.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * §5.5 접수 상태 재계산.
 *
 * 크롤 스냅샷의 상태값은 stale 하다는 것이 이 규칙의 존재 이유다 —
 * 수집 시점에 "접수중" 이었어도 오늘 기준으로는 마감일 수 있다.
 */
class RegistrationStatusTest {

    private val today = LocalDate.of(2026, 6, 1)

    @Test
    fun `접수 마감일이 오늘보다 이전이면 마감이다`() {
        assertEquals(
            RegistrationStatus.CLOSED,
            regStatusOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 31), today = today),
        )
    }

    @Test
    fun `접수 시작일이 오늘보다 이후면 접수전이다`() {
        assertEquals(
            RegistrationStatus.BEFORE,
            regStatusOf(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 7, 1), today = today),
        )
    }

    @Test
    fun `접수 기간 안이면 접수중이다`() {
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30), today = today),
        )
    }

    @Test
    fun `경계일은 접수중이다`() {
        // 마감일 당일 — "regEnd < 오늘" 이 아니므로 아직 접수중이다
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(LocalDate.of(2026, 5, 1), today, today = today),
        )
        // 시작일 당일 — "오늘 < regStart" 가 아니므로 접수중이다
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(today, LocalDate.of(2026, 6, 30), today = today),
        )
    }

    @Test
    fun `날짜가 한쪽만 있어도 재계산한다`() {
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(LocalDate.of(2026, 5, 1), null, today = today),
        )
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(null, LocalDate.of(2026, 6, 30), today = today),
        )
        assertEquals(
            RegistrationStatus.CLOSED,
            regStatusOf(null, LocalDate.of(2026, 5, 31), today = today),
        )
        assertEquals(
            RegistrationStatus.BEFORE,
            regStatusOf(LocalDate.of(2026, 6, 2), null, today = today),
        )
    }

    @Test
    fun `날짜가 있으면 낡은 원본 상태를 무시한다`() {
        // 스냅샷은 "마감"이라 하지만 오늘 기준으로는 접수 기간 안이다
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 6, 30),
                fallback = RegistrationStatus.CLOSED,
                today = today,
            ),
        )
    }

    @Test
    fun `날짜가 전혀 없을 때만 원본 상태를 쓴다`() {
        assertEquals(
            RegistrationStatus.BEFORE,
            regStatusOf(null, null, fallback = RegistrationStatus.BEFORE, today = today),
        )
    }

    @Test
    fun `날짜도 원본도 없으면 미정이다`() {
        assertEquals(RegistrationStatus.UNKNOWN, regStatusOf(null, null, today = today))
    }

    @Test
    fun `표시 문구는 SPEC 표기를 따른다`() {
        assertEquals("접수전", RegistrationStatus.BEFORE.label)
        assertEquals("접수중", RegistrationStatus.OPEN.label)
        assertEquals("마감", RegistrationStatus.CLOSED.label)
        assertEquals("미정", RegistrationStatus.UNKNOWN.label)
    }
}
