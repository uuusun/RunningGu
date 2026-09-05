package com.runninggu.app.ui.home

import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.common.valueOrNull
import com.runninggu.app.ui.model.FestivalSummary
import com.runninggu.app.ui.model.RaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 홈의 영역별 상태. (SPEC §4.4 · AGENTS 2장-5 · 이슈 #49)
 *
 * **핵심은 두 영역이 서로를 가리지 않는 것**이다. 축제는 KTO 프록시라 `502`·`504` 가
 * 실제로 나는데, 그때 우리 DB 에서 온 마감 임박까지 사라지면 홈이 통째로 비어 보인다.
 */
class HomeUiStateTest {

    private fun race(id: String) = RaceSummary(
        id = id,
        name = "대회 $id",
        region = "세종",
        venue = "세종중앙공원",
        date = LocalDate.of(2026, 9, 5),
        startTime = "08:00",
        regStart = LocalDate.of(2026, 4, 1),
        regEnd = LocalDate.of(2026, 8, 30),
        eventTypes = listOf("풀"),
        source = "마라톤GO",
        checked = LocalDate.of(2026, 7, 15),
    )

    private val festival = FestivalSummary(
        id = "1",
        name = "세종 빛 축제",
        region = "세종",
        period = "08.20~08.25",
        isOngoing = true,
    )

    @Test
    fun `축제가 실패해도 마감 임박은 그대로 남는다`() {
        val state = HomeUiState(
            closingSoon = SectionState.Content(listOf(race("a"))),
            festivals = SectionState.Error("외부 API 오류"),
        )

        assertEquals(1, state.closingSoon.valueOrNull?.size)
        assertTrue(state.festivals is SectionState.Error)
    }

    @Test
    fun `마감 임박이 실패해도 축제는 그대로 남는다`() {
        val state = HomeUiState(
            closingSoon = SectionState.Error(null),
            festivals = SectionState.Content(listOf(festival)),
        )

        assertEquals(1, state.festivals.valueOrNull?.size)
    }

    @Test
    fun `한쪽이 로딩이어도 다른 쪽은 먼저 보인다`() {
        // 두 영역을 따로 부르므로 먼저 끝난 쪽이 먼저 그려진다
        val state = HomeUiState(
            closingSoon = SectionState.Content(listOf(race("a"))),
            festivals = SectionState.Loading,
        )

        assertEquals(1, state.closingSoon.valueOrNull?.size)
        assertTrue(state.festivals is SectionState.Loading)
    }

    @Test
    fun `대표 대회는 마감 임박 첫 항목이다`() {
        // 따로 조회하지 않는다 — 두 값이 갈리면 히어로와 목록 첫 카드가 다른 대회가 된다
        val state = HomeUiState(
            closingSoon = SectionState.Content(listOf(race("a"), race("b"))),
        )

        assertEquals("a", state.featured?.id)
    }

    @Test
    fun `마감 임박이 없으면 히어로에 세울 대회도 없다`() {
        assertNull(HomeUiState(closingSoon = SectionState.Empty()).featured)
        assertNull(HomeUiState(closingSoon = SectionState.Loading).featured)
        assertNull(HomeUiState(closingSoon = SectionState.Error(null)).featured)
    }

    @Test
    fun `빈 목록은 Empty 이고 내용 아님이다`() {
        // 정상 0건과 오류를 섞지 않는다 (API 명세 §0-3)
        val empty = emptyList<RaceSummary>().toSectionState()
        val filled = listOf(race("a")).toSectionState()

        assertTrue(empty is SectionState.Empty)
        assertTrue(filled is SectionState.Content)
    }

    @Test
    fun `처음에는 두 영역 다 로딩이다`() {
        val state = HomeUiState()

        assertTrue(state.closingSoon is SectionState.Loading)
        assertTrue(state.festivals is SectionState.Loading)
    }
}
