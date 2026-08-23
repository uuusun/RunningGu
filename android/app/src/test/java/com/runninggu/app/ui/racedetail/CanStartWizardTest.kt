package com.runninggu.app.ui.racedetail

import com.runninggu.app.ui.model.RaceSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 동선 만들기 CTA 를 언제 막는가. (SPEC §4.6 · 결정-46)
 *
 * ## 망가뜨리면 이것만 실패한다
 *
 * `canStartWizard` 에서 `race.hasLocation` 을 빼면 `좌표가 없으면 막는다` 만 실패한다.
 * 비활성·로딩 조건은 그대로 통과하므로 어떤 조건이 사라졌는지 바로 보인다.
 */
class CanStartWizardTest {

    private fun race(
        active: Boolean = true,
        lat: Double? = 37.5285,
        lng: Double? = 126.9326,
    ) = RaceSummary(
        id = "seoul-hangang",
        name = "서울 한강 러닝 페스티벌",
        region = "서울",
        venue = "여의도한강공원",
        date = LocalDate.of(2026, 9, 10),
        startTime = "09:00",
        regStart = LocalDate.of(2026, 8, 1),
        regEnd = LocalDate.of(2026, 9, 1),
        eventTypes = listOf("10K", "5K"),
        source = "마라톤온라인",
        checked = LocalDate.of(2026, 8, 22),
        active = active,
        lat = lat,
        lng = lng,
    )

    private fun loaded(race: RaceSummary) =
        RaceDetailUiState(phase = RaceDetailUiState.Phase.LOADED, race = race)

    @Test
    fun `좌표가 있는 활성 대회는 갈 수 있다`() {
        assertTrue(loaded(race()).canStartWizard)
    }

    @Test
    fun `좌표가 없으면 막는다`() {
        // S6 숙소와 S7 후보가 대회장 좌표로 POI 를 조회한다. 기준이 없으면 들어가 봐야
        // 빈 시트다 — 좌표 전용 안내 UX 는 P1 이라 P0 는 CTA 비활성으로 둔다 (SPEC §4.6)
        assertFalse(loaded(race(lat = null, lng = null)).canStartWizard)
        assertFalse(loaded(race(lat = null)).canStartWizard)
        assertFalse(loaded(race(lng = null)).canStartWizard)
    }

    @Test
    fun `비활성 대회는 좌표가 있어도 막는다`() {
        assertFalse(loaded(race(active = false)).canStartWizard)
    }

    @Test
    fun `아직 로딩 중이면 막는다`() {
        assertFalse(RaceDetailUiState(race = race()).canStartWizard)
    }
}
