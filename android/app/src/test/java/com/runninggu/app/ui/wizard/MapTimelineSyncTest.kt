package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.BlockType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.Poi
import com.runninggu.app.ui.sample.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * S7 지도 ↔ 타임라인 동기화. (SPEC §4.10 · §3-8 · AP-03)
 *
 * §4.10 이 다섯 가지를 요구한다.
 *
 * - 일자 탭 → 활성화 + 지도 재계산 + **첫 핀 활성**
 * - **카드 탭 → 핀 카메라 이동**
 * - **핀 탭 → 카드로 스크롤**
 * - **스크롤 중앙 밴드에 든 카드 자동 활성**
 * - **편집 모드 중 동기화 중단**
 *
 * 밴드는 한 번 걷어냈다가 다시 넣었다. §4.10 이 `LazyList` 를 전제로 적었는데 그때
 * S7 본문이 `Column(verticalScroll)` 이라 임시 구현이 됐기 때문이다(#208 리뷰 합의).
 * #210 이 `LazyColumn` 으로 바꾼 뒤 `layoutInfo` 기준으로 다시 붙였다.
 *
 * 밴드가 "들었는가" 를 재는 것은 [centeredBlockId] 가 하고 화면이 좌표를 넘긴다 —
 * 여기서는 들어온 뒤에 무엇이 되는지만 고정한다.
 */
class MapTimelineSyncTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(private val result: ItineraryResult) : ItineraryRepository {
        override suspend fun generate(request: GenerateItineraryRequest) = result
    }

    /** 이틀치. 첫날은 좌표 있는 블록 사이에 좌표 없는 블록이 끼어 있다. */
    private fun result() = ItineraryResult(
        title = "1박 2일",
        request = ItineraryRequestSnapshot(1, "HALF", listOf("TOUR"), "2026-09-04", "2026-09-05", null),
        days = listOf(
            day(
                0,
                block("blk_1", poi(37.52, 126.93)),
                block("blk_2", null),
                block("blk_3", poi(37.54, 126.95)),
            ),
            day(1, block("blk_9", poi(37.60, 127.00))),
        ),
        recovery = null,
        recoveryFlags = listOf(false, true),
    )

    private fun loaded(): ResultViewModel = ResultViewModel(repository = FakeRepository(result()))

    private fun wizard(): WizardUiState {
        val race = SampleData.races.first().copy(serverId = 1L)
        return WizardUiState(race = race, start = race.date, end = race.date)
    }

    // ── 첫 핀 활성 (§4.10) ─────────────────────────────────────

    @Test
    fun `동선이 오면 첫 핀이 활성이다`() = runTest(dispatcher) {
        // 아무것도 안 골라져 있으면 타임라인에 강조된 카드가 없어 어디부터 보는지 모른다
        val viewModel = loaded()

        viewModel.generate(wizard())
        advanceUntilIdle()

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `일자를 옮기면 그 일자의 첫 핀이 활성이 된다`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onDaySelect(1)

        // 어제 고른 블록이 남으면 카메라가 어제 자리로 간다
        assertEquals("blk_9", viewModel.uiState.value.activePinId)
    }

    // ── 카드 탭 → 핀 (§4.10) ───────────────────────────────────

    @Test
    fun `카드를 누르면 그 핀이 활성이 된다`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onCardClick("blk_3")

        assertEquals("blk_3", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `좌표 없는 카드를 눌러도 활성이 바뀌지 않는다`() = runTest(dispatcher) {
        // 카메라가 갈 곳이 없다. 지도는 그대로인데 카드만 강조되면 왜 안 움직이는지 모른다
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onCardClick("blk_2")

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    // ── 핀 탭 (§3-8) ───────────────────────────────────────────

    @Test
    fun `같은 핀을 다시 눌러도 풀리지 않는다`() = runTest(dispatcher) {
        // 계약은 "핀 탭 → 해당 항목 활성" 뿐이다. 해제로 만들면 강조만 사라지고 카메라는
        // 확대된 채 남는다 — cameraCommandFor 가 활성 null 에는 None 을 준다 (#208 리뷰)
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onPinClick("blk_1")

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `다른 핀을 누르면 그쪽으로 옮겨간다`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onPinClick("blk_3")

        assertEquals("blk_3", viewModel.uiState.value.activePinId)
    }

    // ── 스크롤 중앙 밴드 → 활성 (§4.10) ────────────────────────
    //
    // 밴드에 "들었는가" 를 재는 것은 [centeredBlockId] 가 하고 화면이 좌표를 넘긴다.
    // 여기서는 **들어온 뒤에 무엇이 되는가**를 고정한다.

    @Test
    fun `중앙에 든 카드가 활성이 된다`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onCardCentered("blk_3")

        assertEquals("blk_3", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `좌표 없는 카드는 가운데 와도 활성이 되지 않는다`() = runTest(dispatcher) {
        // 카메라가 갈 곳이 없다. 강조만 옮겨 다니면 지도가 고장 난 것처럼 보인다 —
        // 가운데 것과 다른 카드가 강조된 채로 지나가는 편이 낫다
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()

        viewModel.onCardCentered("blk_2")

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `편집 중에는 가운데 와도 활성이 안 바뀐다`() = runTest(dispatcher) {
        // 화면도 편집 중에는 부르지 않지만, 부르는 자리가 여럿이라 여기서도 막는다
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()
        viewModel.onToggleEdit()

        viewModel.onCardCentered("blk_3")

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    // ── 편집 중 동기화 중단 (§4.10) ────────────────────────────

    @Test
    fun `편집 중에는 핀을 눌러도 활성이 안 바뀐다`() = runTest(dispatcher) {
        // 편집 중에는 행을 옮기고 지우는 중이라, 카드가 저절로 스크롤되면 누르려던
        // 버튼이 발밑에서 움직인다
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()
        viewModel.onToggleEdit()

        viewModel.onPinClick("blk_3")

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `편집 중에는 카드를 눌러도 활성이 안 바뀐다`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()
        viewModel.onToggleEdit()

        viewModel.onCardClick("blk_3")

        assertEquals("blk_1", viewModel.uiState.value.activePinId)
    }

    @Test
    fun `편집을 끝내면 다시 바뀐다`() = runTest(dispatcher) {
        val viewModel = loaded()
        viewModel.generate(wizard())
        advanceUntilIdle()
        viewModel.onToggleEdit()
        viewModel.onToggleEdit()

        viewModel.onCardClick("blk_3")

        assertEquals("blk_3", viewModel.uiState.value.activePinId)
    }

    // ── 도구 ──────────────────────────────────────────────────

    private fun day(off: Int, vararg blocks: ItineraryBlock) = ItineraryDay(
        date = LocalDate.of(2026, 9, 4).plusDays(off.toLong()),
        off = off,
        label = if (off == 0) "D-day" else "D+$off",
        dateLabel = "09.0${4 + off}",
        note = "",
        blocks = blocks.toList(),
    )

    private fun block(id: String, place: Poi?) = ItineraryBlock(
        id = id,
        time = "10:00",
        title = "블록 $id",
        catKey = BlockCategory.TOUR,
        place = place,
        desc = "",
        blockType = BlockType.USER,
    )

    private fun poi(lat: Double, lng: Double) = Poi("장소", lat, lng, "주소")
}
