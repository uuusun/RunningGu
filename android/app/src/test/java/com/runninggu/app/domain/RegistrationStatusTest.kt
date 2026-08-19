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
    fun `시작일만 알아도 단정할 수 있으면 재계산한다`() {
        // 시작일을 지났다 → 접수는 시작됐다
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(LocalDate.of(2026, 5, 1), null, today = today),
        )
        // 시작일이 아직 안 됐다
        assertEquals(
            RegistrationStatus.BEFORE,
            regStatusOf(LocalDate.of(2026, 6, 2), null, today = today),
        )
        // 마감일이 지났다
        assertEquals(
            RegistrationStatus.CLOSED,
            regStatusOf(null, LocalDate.of(2026, 5, 31), today = today),
        )
    }

    @Test
    fun `마감일만 알고 아직 안 지났으면 단정하지 않는다`() {
        // 마감일이 미래라는 것만으로는 접수중이라고 할 수 없다 — 아직 접수 시작 전일 수 있다.
        // API 명세 §3-1 목록 응답에 applyStart 가 없어 실제로 생기는 상황이다.
        assertEquals(
            RegistrationStatus.BEFORE,
            regStatusOf(
                null,
                LocalDate.of(2026, 6, 30),
                fallback = RegistrationStatus.BEFORE,
                today = today,
            ),
        )
        assertEquals(
            RegistrationStatus.OPEN,
            regStatusOf(
                null,
                LocalDate.of(2026, 6, 30),
                fallback = RegistrationStatus.OPEN,
                today = today,
            ),
        )
        // 서버 값도 없으면 미정이다 — 접수중이라고 넘겨짚지 않는다
        assertEquals(
            RegistrationStatus.UNKNOWN,
            regStatusOf(null, LocalDate.of(2026, 6, 30), today = today),
        )
    }

    @Test
    fun `마감일이 지났으면 서버 값보다 우선한다`() {
        // 캐시된 응답이 "접수중" 이어도 오늘 기준으로 마감이면 마감이다
        assertEquals(
            RegistrationStatus.CLOSED,
            regStatusOf(
                null,
                LocalDate.of(2026, 5, 31),
                fallback = RegistrationStatus.OPEN,
                today = today,
            ),
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
