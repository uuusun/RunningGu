package com.runninggu.app.ui.wizard

import com.runninggu.app.ui.model.RaceSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 위저드가 대회 조회 상태를 **무엇으로 그리는가.** (이슈 #140 · #189 후속 · SPEC §3-5)
 *
 * #189 가 `contestPhase` 를 세웠지만 화면은 여전히 `race == null` 을 로딩으로 읽고 있었다.
 * 그래서 **조회에 실패해도 스피너가 계속 돌았다** — 이 파일이 그 자리를 막는다.
 *
 * 가장 중요한 것은 **재시도를 주는 기준**이다. `NOT_FOUND` 에 [다시 시도] 를 붙이면
 * 사용자가 없는 대회를 계속 다시 부른다.
 */
class WizardContestViewTest {

    private val race = RaceSummary(
        id = "7",
        serverId = 7,
        name = "세종 호수공원 마라톤",
        region = "세종",
        venue = "세종 호수공원",
        date = LocalDate.of(2026, 9, 12),
        startTime = "09:00",
        regStart = null,
        regEnd = null,
        eventTypes = listOf("HALF"),
        source = "MARATHON_ONLINE",
        checked = null,
    )

    @Test
    fun `조회 중에는 로딩이다`() {
        val view = WizardUiState(contestPhase = WizardUiState.Phase.LOADING).contestView()

        assertEquals(WizardContestView.Loading, view)
    }

    @Test
    fun `실패는 다시 시도를 준다`() {
        val view = WizardUiState(
            contestPhase = WizardUiState.Phase.ERROR,
            errorMessage = "잠시 후 다시 시도해 주세요.",
        ).contestView()

        val failed = view as WizardContestView.Failed
        assertTrue(failed.retryable)
        // 서버가 준 말을 쓴다 — 왜 실패했는지는 서버가 더 잘 안다.
        assertEquals("잠시 후 다시 시도해 주세요.", failed.title)
    }

    @Test
    fun `서버 문구가 없으면 기본 문구로 떨어진다`() {
        val view = WizardUiState(contestPhase = WizardUiState.Phase.ERROR).contestView()

        assertEquals("대회 정보를 못 불러왔어요.", (view as WizardContestView.Failed).title)
    }

    @Test
    fun `없는 대회는 다시 시도를 주지 않는다`() {
        // 404 와 canonical id 가 없는 대회가 여기 온다. 다시 눌러도 생기지 않는다(#139 · #189).
        val view = WizardUiState(contestPhase = WizardUiState.Phase.NOT_FOUND).contestView()

        val failed = view as WizardContestView.Failed
        assertFalse(failed.retryable)
        assertEquals("대회 정보를 찾을 수 없어요.", failed.title)
        assertEquals("삭제됐거나 주소가 잘못됐을 수 있어요.", failed.description)
    }

    @Test
    fun `실었으면 대회를 그대로 넘긴다`() {
        val view = WizardUiState(
            contestPhase = WizardUiState.Phase.LOADED,
            race = race,
        ).contestView()

        assertEquals(race, (view as WizardContestView.Ready).race)
    }

    @Test
    fun `LOADED 인데 대회가 없으면 로딩으로 둔다`() {
        // ViewModel 이 만들 수 없는 조합이다. 화면이 크래시로 갚을 자리는 아니다.
        val view = WizardUiState(contestPhase = WizardUiState.Phase.LOADED).contestView()

        assertEquals(WizardContestView.Loading, view)
    }

    @Test
    fun `다음으로 갈 수 있는 것은 실었을 때뿐이다`() {
        // 실패·없음 화면에 [다음] 이 붙으면 눌러도 아무 일이 안 일어난다.
        WizardUiState.Phase.entries.forEach { phase ->
            val ready = WizardUiState(contestPhase = phase, race = race).contestView()
            assertEquals(
                "$phase 에서 Ready 여부가 어긋난다",
                phase == WizardUiState.Phase.LOADED,
                ready is WizardContestView.Ready,
            )
        }
    }
}
