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
import org.junit.Assert.assertFalse
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
        hotel: HotelSnapshot? = HotelSnapshot("호텔", 36.50, 127.25),
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
                hotel = hotel,
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

    @Test
    fun `복원하면 편집과 저장이 닫힌다`() = runTest(dispatcher) {
        // **P0 에서 저장 후 편집은 블록 API 로 간다** (§5-7~5-10 · #213).
        // `POST /api/itineraries` 로 통째 저장하면 서버가 현재 canonical 대회로 RACE 를
        // 재구성해(§5-2), USER 장소 하나만 고쳐도 저장 snapshot 의 대회가 말없이 바뀐다.
        // 그 길이 화면에 열려 있으면 안 된다
        val viewModel = ResultViewModel(repository = FakeDetailRepository(detail = detail()))
        viewModel.restore(42L)
        advanceUntilIdle()

        assertEquals(ResultUiState.Phase.CONTENT, viewModel.uiState.value.phase)
        assertFalse(
            "복원 화면에서 편집·저장이 열렸다 — A 경로로 통째 저장된다",
            viewModel.uiState.value.editingEnabled,
        )
    }

    @Test
    fun `생성 경로는 편집과 저장을 그대로 연다`() {
        // **반대쪽도 고정한다.** 안 그러면 `editingEnabled` 를 늘 false 로 만들어도 위
        // 테스트가 통과한다 — 그러면 S7 에서 저장 자체를 못 하게 된다
        assertTrue(ResultUiState().editingEnabled)
        assertFalse(ResultUiState(restoredItineraryId = 42L).editingEnabled)
    }


    @Test
    fun `복원 오류에서 다시 시도하면 그 동선을 다시 조회한다`() = runTest(dispatcher) {
        // **예전에는 아무 일도 안 했다** (#257 리뷰). `retry()` 가 생성 경로의
        // `lastRequest` 만 다시 보내는데 복원 진입에는 그게 없다 — 오류 화면에 버튼만
        // 있고 눌러도 그대로였다
        val repo = FakeDetailRepository(failure = IOException("boom"))
        val viewModel = ResultViewModel(repository = repo)
        viewModel.restore(42L)
        advanceUntilIdle()
        assertEquals(ResultUiState.Phase.ERROR, viewModel.uiState.value.phase)
        assertEquals(1, repo.detailCalls)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, repo.detailCalls)
    }

    @Test
    fun `대회가 바뀌었으면 상태가 그것을 들고 있다`() = runTest(dispatcher) {
        // 화면이 이 값으로 안내를 띄운다. 안 알리면 저장해 둔 일정이 최신 대회와
        // 어긋난 채로 정상처럼 보인다 (매핑표 S7-R)
        val viewModel = ResultViewModel(
            repository = FakeDetailRepository(detail = detail(needsRegeneration = true)),
        )
        viewModel.restore(42L)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.needsRegeneration)
    }


    // **복원 화면에서만 숙소 연계가 끊겨 있었다** (#257 리뷰). 내비게이션이 항상
    // `stay = null` 을 넘겨서, 생성 직후 S7 에서는 되던 [숙소 주변에서 뛰기·걷기] 가
    // 저장한 동선을 열면 출발지 없이 열렸다. 값은 응답에 있었는데 아무도 안 읽었다.
    @Test
    fun `복원한 동선은 저장해 둔 숙소를 S8 출발지로 넘긴다`() = runTest {
        val vm = viewModel(FakeDetailRepository(detail()))
        vm.restore(7L)
        advanceUntilIdle()

        val stay = vm.uiState.value.courseStay
        assertEquals("호텔", stay?.name)
        assertEquals(36.50, stay?.lat)
        assertEquals(127.25, stay?.lng)
    }

    // 숙소 없이 추천받은 동선(§4.9)은 출발지를 프리필하지 않는다. 위와 같은 값을
    // 내면 사용자가 고르지도 않은 곳이 출발지로 잡힌다.
    @Test
    fun `숙소 없이 저장한 동선은 출발지를 넘기지 않는다`() = runTest {
        val vm = viewModel(FakeDetailRepository(detail(hotel = null)))
        vm.restore(7L)
        advanceUntilIdle()

        assertNull(vm.uiState.value.courseStay)
    }

}
