package com.runninggu.app.ui.home

import com.runninggu.app.ui.OFFLINE
import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.model.Festival
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.remote.httpErrorOf
import com.runninggu.app.data.repository.ClosingSoon
import com.runninggu.app.data.repository.ContestFilter
import com.runninggu.app.data.repository.ContestPage
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.data.repository.FestivalRepository
import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import com.runninggu.app.ui.common.SectionState
import com.runninggu.app.ui.common.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * S1 홈이 두 영역을 **따로** 조회한다. (SPEC §4.4 · AGENTS 2장-5 · AP-14)
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * 1. **`limit` 은 4** — 서버 계약이 `1~4` 라 벗어나면 `400 VALIDATION_FAILED` 다(§3-3)
 * 2. **서버가 준 순서를 앱이 다시 만들지 않는다** — 거르고 정렬하는 주인이 서버다
 * 3. **한 영역이 죽어도 다른 영역은 산다** — 축제는 KTO 프록시라 `502` 가 실제로 난다
 *
 * > 이 파일은 `ClosingSoonLimitTest` 를 대신한다. 그 테스트는 앱이 직접 `.take(4)` 하던
 * > 시절에 **잘린 건수**를 셌는데, 이제 자르는 주인이 서버라 셀 것이 없다. 대신 **서버에
 * > 무엇을 넘기는지**를 같은 이유(§3-3 `limit` 범위)로 고정한다.
 */
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `마감 임박은 limit 4 로 조회한다`() = runTest(dispatcher) {
        val contests = RecordingContestRepository()

        HomeViewModel(contests, StubFestivalRepository())
        advanceUntilIdle()

        // 5 를 넘기면 서버가 400 이다 — 홈의 한 영역이 통째로 오류가 된다 (§3-3)
        assertEquals(4, contests.lastLimit)
    }

    @Test
    fun `서버가 준 순서를 그대로 쓴다`() = runTest(dispatcher) {
        // 일부러 마감일 역순으로 준다. 앱이 다시 정렬하면 이 단언이 깨진다.
        val contests = RecordingContestRepository(
            closingSoon = listOf(
                closingSoon(1, LocalDate.of(2026, 9, 20)),
                closingSoon(2, LocalDate.of(2026, 9, 1)),
            ),
        )

        val viewModel = HomeViewModel(contests, StubFestivalRepository())
        advanceUntilIdle()

        val ids = viewModel.uiState.value.closingSoon.valueOrNull.orEmpty().map { it.id }
        assertEquals(listOf("1", "2"), ids)
    }

    @Test
    fun `축제가 죽어도 마감 임박은 남는다`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(
            RecordingContestRepository(closingSoon = listOf(closingSoon(1, LocalDate.of(2026, 9, 1)))),
            StubFestivalRepository(failure = ApiException.Network(java.io.IOException("KTO 끊김"))),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.festivals is SectionState.Error)
        // 이것이 이 화면의 전부다 — KTO 가 죽었다고 우리 DB 대회가 가려지면 안 된다
        assertEquals(1, state.closingSoon.valueOrNull.orEmpty().size)
    }

    @Test
    fun `축제 오류 문구가 없으면 화면 기본 문구에 맡긴다`() = runTest(dispatcher) {
        // **#260 이 났던 자리는 함수가 아니라 ViewModel 이 어느 함수를 부르는가였다**
        // (#272 리뷰). `sectionMessage()` 만 따로 부르는 테스트는 호출부를
        // `userMessageOrDefault()` 로 되돌려도 초록불이다.
        //
        // 프록시가 HTML 오류 페이지를 주면 problem 이 null 이라 서버 문구가 없다.
        // 그때 null 이어야 화면의 "축제 정보를 불러오지 못했어요" 가 산다
        val viewModel = HomeViewModel(
            RecordingContestRepository(closingSoon = listOf(closingSoon(1, LocalDate.of(2026, 9, 1)))),
            StubFestivalRepository(failure = httpErrorOf(502, "<html>502</html>")),
        )
        advanceUntilIdle()

        assertNull((viewModel.uiState.value.festivals as SectionState.Error).message)
    }

    @Test
    fun `마감 임박 오류도 같은 규칙이다`() = runTest(dispatcher) {
        // 두 영역이 같은 헬퍼를 쓰는지 본다 — 한쪽만 고치면 나머지가 남는다
        val viewModel = HomeViewModel(
            RecordingContestRepository(failure = httpErrorOf(500, null)),
            StubFestivalRepository(festivals = listOf(festival())),
        )
        advanceUntilIdle()

        assertNull((viewModel.uiState.value.closingSoon as SectionState.Error).message)
    }

    @Test
    fun `네트워크가 끊기면 그 문구는 ViewModel 이 채운다`() = runTest(dispatcher) {
        // 반대쪽도 고정한다. 전부 null 로 만들어도 위 두 개가 통과하기 때문이다 —
        // 그러면 연결 문제일 때 "축제 정보를 불러오지 못했어요" 만 나온다
        val viewModel = HomeViewModel(
            RecordingContestRepository(closingSoon = listOf(closingSoon(1, LocalDate.of(2026, 9, 1)))),
            StubFestivalRepository(failure = ApiException.Network(java.io.IOException("끊김"))),
        )
        advanceUntilIdle()

        assertEquals(
            OFFLINE,
            (viewModel.uiState.value.festivals as SectionState.Error).message,
        )
    }

    @Test
    fun `마감 임박이 죽어도 축제는 남는다`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(
            RecordingContestRepository(failure = ApiException.Network(java.io.IOException("끊김"))),
            StubFestivalRepository(festivals = listOf(festival())),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.closingSoon is SectionState.Error)
        assertEquals(1, state.festivals.valueOrNull.orEmpty().size)
        // 히어로는 마감 임박 첫 항목이라, 그쪽이 오류면 대회 없이 그린다 (§4.4-2)
        assertNull(state.featured)
    }

    @Test
    fun `정상 0건은 빈 상태다`() = runTest(dispatcher) {
        val viewModel = HomeViewModel(RecordingContestRepository(), StubFestivalRepository())
        advanceUntilIdle()

        // 오류와 섞지 않는다 — 사용자가 다시 시도할 상황인지 갈린다 (§0-3)
        assertTrue(viewModel.uiState.value.closingSoon is SectionState.Empty)
        assertTrue(viewModel.uiState.value.festivals is SectionState.Empty)
    }

    private fun closingSoon(id: Long, regEnd: LocalDate) = ClosingSoon(
        contest = contest(id, regEnd),
        dDayApply = null,
    )

    private fun contest(id: Long, regEnd: LocalDate) = Contest(
        id = id.toString(),
        serverId = id,
        name = "대회 $id",
        region = "세종",
        venue = "세종중앙공원",
        date = regEnd.plusDays(14),
        startTime = null,
        eventTypes = listOf(EventType.HALF),
        regStart = null,
        regEnd = regEnd,
        regStatusFallback = RegistrationStatus.OPEN,
        organizer = null,
        officialUrl = null,
        detailUrl = null,
        imageUrl = null,
        lat = null,
        lng = null,
        category = null,
        checked = null,
        sources = listOf("MARATHON_GO"),
    )

    private fun festival() = Festival(
        contentId = "fest-1",
        name = "세종 물빛축제",
        startDate = LocalDate.of(2026, 8, 20),
        endDate = LocalDate.of(2026, 8, 24),
        region = "세종",
        imageUrl = null,
        inProgress = false,
    )
}

/** 넘겨받은 `limit` 을 기록하는 가짜 저장소. 홈은 [ContestRepository.closingSoon] 만 쓴다. */
private class RecordingContestRepository(
    private val closingSoon: List<ClosingSoon> = emptyList(),
    private val failure: Throwable? = null,
) : ContestRepository {

    var lastLimit: Int? = null
        private set

    override suspend fun closingSoon(limit: Int): List<ClosingSoon> {
        lastLimit = limit
        failure?.let { throw it }
        return closingSoon
    }

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage =
        throw UnsupportedOperationException("홈은 부르지 않는다")

    override suspend fun dailyCounts(
        year: Int,
        month: Int,
        filter: ContestFilter,
    ): Map<LocalDate, Int> = throw UnsupportedOperationException("홈은 부르지 않는다")

    override suspend fun detail(id: Long): Contest =
        throw UnsupportedOperationException("홈은 부르지 않는다")

    override suspend fun festivals(id: Long): List<NearbyFestival> =
        throw UnsupportedOperationException("홈은 부르지 않는다")
}

private class StubFestivalRepository(
    private val festivals: List<Festival> = emptyList(),
    private val failure: Throwable? = null,
) : FestivalRepository {

    override suspend fun list(yearMonth: YearMonth?, size: Int): List<Festival> {
        failure?.let { throw it }
        return festivals
    }
}
