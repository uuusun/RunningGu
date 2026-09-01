package com.runninggu.app.ui.wizard

import com.runninggu.app.data.model.ContestSnapshot
import com.runninggu.app.data.model.HotelSnapshot
import com.runninggu.app.data.model.ItineraryRequestSnapshot
import com.runninggu.app.data.model.ItineraryResult
import com.runninggu.app.data.model.SavedItineraryDetail
import com.runninggu.app.data.remote.ApiErrorCode
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.GenerateItineraryRequest
import com.runninggu.app.data.repository.ItineraryRepository
import com.runninggu.app.domain.BlockCategory
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.ItineraryBlock
import com.runninggu.app.domain.ItineraryDay
import com.runninggu.app.domain.Poi
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 저장한 동선을 되살릴 때 **화면이 생성과 같은 상태가 되는가.** (S7-R · §5-5 · #213)
 *
 * #213 의 결론이 *"새 화면을 만들지 않고 S7 을 재사용한다"* 였다. 그러려면 두 경로가
 * **같은 `ResultUiState` 를 만들어야** 한다 — 편집·지도·저장이 상태 하나를 보기 때문에,
 * 한쪽만 다르게 채우면 그 기능들이 복원 화면에서만 조용히 다르게 돈다.
 *
 * 그래서 이 파일은 문구가 아니라 **채워진 모양**을 고정한다.
 */
class RestoreItineraryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeDetailRepository(
        private val detail: SavedItineraryDetail? = null,
        private val failure: Throwable? = null,
    ) : ItineraryRepository {
        var detailCalls = 0
            private set

        override suspend fun detail(id: Long): SavedItineraryDetail {
            detailCalls++
            failure?.let { throw it }
            return detail!!
        }

        override suspend fun generate(request: GenerateItineraryRequest): ItineraryResult =
            throw UnsupportedOperationException("이 테스트는 복원만 본다")
    }

    private fun detail(
        days: List<ItineraryDay> = listOf(day(0, block("blk_1", poi(37.52, 126.93)))),
        event: String = "K10",
        region: String? = "세종",
        needsRegeneration: Boolean = false,
    ) = SavedItineraryDetail(
        id = 7L,
        result = ItineraryResult(
            title = "세종 1박 2일",
            request = ItineraryRequestSnapshot(
                contestId = 1L,
                event = event,
                themes = listOf("TOUR"),
                startDate = "2026-09-04",
                endDate = "2026-09-05",
                hotel = HotelSnapshot("호텔", 36.50, 127.25),
            ),
            days = days,
            recovery = null,
            recoveryFlags = days.map { false },
        ),
        region = region,
        needsRegeneration = needsRegeneration,
        contest = ContestSnapshot(
            name = "세종 마라톤",
            region = "세종",
            place = "호수공원",
            contestDate = "2026-09-05",
            startTime = "08:00",
            lat = 36.48,
            lng = 127.28,
            active = true,
        ),
    )

    private fun viewModel(repo: ItineraryRepository) = ResultViewModel(repository = repo)

    @Test
    fun `저장한 동선을 되살리면 생성과 같은 모양이 된다`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeDetailRepository(detail()))
        viewModel.restore(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ResultUiState.Phase.CONTENT, state.phase)
        assertEquals("세종 1박 2일", state.result?.title)
        assertEquals("세종", state.region)
        // **첫 핀이 활성이어야 한다.** 생성 경로가 그렇게 두는데(#213 · §4.10) 여기서
        // 빠지면 복원 화면만 강조된 카드 없이 열린다
        assertEquals(state.mapPins.firstOrNull()?.id, state.activeBlockId)
        assertEquals(7L, state.restoredItineraryId)
    }

    @Test
    fun `서버 종목 표기를 도메인 종목으로 되돌린다`() = runTest(dispatcher) {
        // K10 을 그대로 두거나 기본값으로 흘리면 회복 배지가 다른 종목 기준으로 뜬다
        val viewModel = viewModel(FakeDetailRepository(detail(event = "K10")))
        viewModel.restore(7L)
        advanceUntilIdle()

        assertEquals(EventType.TEN_K, viewModel.uiState.value.event)
    }

    @Test
    fun `대회가 바뀌었으면 그 사실을 든다`() = runTest(dispatcher) {
        // 일정은 저장 시점 snapshot 그대로 두고 **바뀐 사실만** 알린다 (§5-3)
        val viewModel = viewModel(FakeDetailRepository(detail(needsRegeneration = true)))
        viewModel.restore(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("대회 변경을 안 들었다", state.needsRegeneration)
        assertEquals("일정이 최신 대회로 갈아 끼워졌다", "세종 1박 2일", state.result?.title)
    }

    @Test
    fun `조회에 실패하면 오류로 남고 내용은 비운다`() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeDetailRepository(failure = ApiException.Network(IOException("끊김"))),
        )
        viewModel.restore(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ResultUiState.Phase.ERROR, state.phase)
        assertNull("실패했는데 내용이 남았다", state.result)
        assertNotNull(state.errorMessage)
        // 어느 동선을 열려다 실패했는지는 들고 있어야 [다시 시도] 가 가능하다
        assertEquals(7L, state.restoredItineraryId)
    }

    @Test
    fun `서버가 준 문구가 있으면 그걸 쓴다`() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeDetailRepository(
                failure = ApiException.Http(
                    status = 404,
                    code = ApiErrorCode.UNKNOWN,
                    problem = null,
                ),
            ),
        )
        viewModel.restore(7L)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `같은 동선으로 다시 들어와도 두 번 조회하지 않는다`() = runTest(dispatcher) {
        // 회전·재진입으로 LaunchedEffect 가 다시 도는 경우다. 생성 경로와 같은 규칙을 쓴다
        val repo = FakeDetailRepository(detail())
        val viewModel = viewModel(repo)
        viewModel.restore(7L)
        advanceUntilIdle()
        viewModel.restore(7L)
        advanceUntilIdle()

        assertEquals(1, repo.detailCalls)
    }

    @Test
    fun `다른 동선을 열면 다시 조회한다`() = runTest(dispatcher) {
        // 막는 것이 과하면 보관함에서 다른 카드를 눌러도 앞의 것이 보인다
        val repo = FakeDetailRepository(detail())
        val viewModel = viewModel(repo)
        viewModel.restore(7L)
        advanceUntilIdle()
        viewModel.restore(9L)
        advanceUntilIdle()

        assertEquals(2, repo.detailCalls)
        assertEquals(9L, viewModel.uiState.value.restoredItineraryId)
    }

    // ── 조각 ────────────────────────────────────────────────────

    private fun poi(lat: Double, lng: Double) = Poi(name = "장소", lat = lat, lng = lng)

    private fun block(id: String, poi: Poi?) = ItineraryBlock(
        id = id,
        time = "10:00",
        title = "블록",
        catKey = BlockCategory.TOUR,
        place = poi,
        desc = "",
    )

    private fun day(index: Int, vararg blocks: ItineraryBlock) = ItineraryDay(
        date = LocalDate.parse("2026-09-0${4 + index}"),
        off = index - 1,
        label = if (index == 0) "D-1" else "D-day",
        dateLabel = "09.0${4 + index}",
        note = "",
        blocks = blocks.toList(),
    )
}
