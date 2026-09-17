package com.runninggu.app.ui.wizard

import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.domain.TripPattern
import com.runninggu.app.ui.model.RaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * S4 직접 선택의 기간 규칙 — 최대 7일 · 대회일 포함. (SPEC §4.7 🔒확정)
 *
 * 2026-09-17 QA(G-04)에서 `10.04 ~ 10.19`(16일)도, `10.04 ~ 10.10`(대회일 없음)도
 * [다음] 이 넘어가는 것을 찾았다. [WizardUiState.canProceed] 가 종료일을 골랐는지만 봤다.
 * 이 파일은 그 자리를 지킨다 — 규칙을 빼면 아래 두 테스트가 떨어진다.
 */
class PlanRangeRuleTest {

    private val raceDate: LocalDate = LocalDate.of(2026, 10, 18)

    private val race = RaceSummary(
        id = "131",
        serverId = 131,
        name = "2026 아시아 오픈 마라톤",
        region = "서울",
        venue = "서울 광화문 광장",
        date = raceDate,
        startTime = "08:00",
        regStart = null,
        regEnd = null,
        eventTypes = listOf("하프", "10K"),
        source = "마라톤GO",
        checked = null,
        regStatusFallback = RegistrationStatus.OPEN,
    )

    private fun custom(start: LocalDate, end: LocalDate) = WizardUiState(
        race = race,
        pattern = TripPattern.CUSTOM,
        start = start,
        end = end,
        awaitingEndDate = false,
    )

    @Test
    fun `8일 이상이면 다음으로 못 간다`() {
        // 10.04 ~ 10.19 = 16일 — QA 에서 실제로 넘어갔던 입력
        val state = custom(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 19))
        assertEquals(16, state.dayCount)
        assertFalse(state.isRangeValid)
        assertFalse(state.canProceed)

        // 정확히 7일은 된다 — 상한은 포함이다
        val seven = custom(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        assertEquals(WizardUiState.MAX_TRIP_DAYS, seven.dayCount)
        assertTrue(seven.canProceed)
    }

    @Test
    fun `대회일이 빠진 기간이면 다음으로 못 간다`() {
        // 10.04 ~ 10.10 — 7일이라 길이는 되는데 대회일(10.18)이 없다
        val before = custom(LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 10))
        assertFalse(before.canProceed)

        // 대회 뒤로만 잡아도 마찬가지
        val after = custom(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21))
        assertFalse(after.canProceed)

        // 대회일 하루만 골라도 된다(당일치기와 같은 모양)
        assertTrue(custom(raceDate, raceDate).canProceed)
    }

    @Test
    fun `패턴 칩이 만든 기간은 항상 규칙에 맞는다`() {
        // 칩은 대회일 기준으로 만들어지므로 규칙을 추가해도 막히면 안 된다
        TripPattern.entries.filter { it != TripPattern.CUSTOM }.forEach { pattern ->
            val range = pattern.rangeOf(raceDate)!!
            val state = WizardUiState(
                race = race,
                pattern = pattern,
                start = range.start,
                end = range.endInclusive,
            )
            assertTrue("$pattern", state.canProceed)
        }
    }

    @Test
    fun `종료일을 아직 안 골랐으면 규칙과 상관없이 막는다`() {
        val state = WizardUiState(race = race, pattern = TripPattern.CUSTOM, start = raceDate, end = null, awaitingEndDate = true)
        assertFalse(state.canProceed)
    }
}
