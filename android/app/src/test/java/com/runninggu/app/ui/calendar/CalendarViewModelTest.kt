package com.runninggu.app.ui.calendar

import com.runninggu.app.data.model.Contest
import com.runninggu.app.data.model.NearbyFestival
import com.runninggu.app.data.remote.ApiException
import com.runninggu.app.data.repository.ClosingSoon
import com.runninggu.app.data.repository.ContestFilter
import com.runninggu.app.data.repository.ContestPage
import com.runninggu.app.data.repository.ContestRepository
import com.runninggu.app.domain.EventType
import com.runninggu.app.domain.RegistrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * S2 캘린더 ViewModel. (SPEC §4.5 · #85 리뷰)
 *
 * **거르는 일을 서버가 하므로**(§3-1), 조건이 서버로 제대로 나가는지와 사용자가 보고 있는
 * 달이 마음대로 바뀌지 않는지가 이 화면의 핵심이다. 둘 다 화면을 봐야 알 수 있는 증상이라
 * 여기서 고정한다.
 */
class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: RecordingContestRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = RecordingContestRepository()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `홈에서 넘어온 검색어가 첫 서버 조회에 들어간다`() = runTest(dispatcher) {
        // 검색어 없이 받은 첫 20건을 앱에서 다시 걸러 봐야, 찾는 대회가 그 밖에 있으면
        // 조건에 맞는데도 빈 화면이 된다 (#85 리뷰)
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.applyInitialQuery("세종")
        advanceUntilIdle()

        assertEquals("세종", repository.lastFilter?.query)
    }

    @Test
    fun `검색어가 비어 있으면 다시 조회하지 않는다`() = runTest(dispatcher) {
        // 홈에서 그냥 캘린더 탭으로 들어온 경우다. 헛 왕복을 만들지 않는다
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()
        val callsAfterInit = repository.listCalls

        viewModel.applyInitialQuery("")
        advanceUntilIdle()

        assertEquals(callsAfterInit, repository.listCalls)
        assertNull(repository.lastFilter?.query)
    }

    @Test
    fun `날짜 선택을 해제해도 보고 있던 달이 유지된다`() = runTest(dispatcher) {
        // 10월을 보다가 재탭으로 해제했는데 8월로 튀면, 해제가 달을 옮기는 동작이 된다
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.onViewModeChange(CalendarViewMode.CALENDAR)
        viewModel.onMonthChange(2)
        advanceUntilIdle()
        val watching = viewModel.uiState.value.currentMonth

        viewModel.onDateSelect(watching.atDay(10))
        advanceUntilIdle()
        assertEquals(watching, viewModel.uiState.value.currentMonth)

        // 같은 날짜를 다시 눌러 해제한다 (SPEC §4.5)
        viewModel.onDateSelect(watching.atDay(10))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedDate)
        assertEquals(watching, viewModel.uiState.value.currentMonth)
    }

    @Test
    fun `첫 조회는 결과가 있는 달을 연다`() = runTest(dispatcher) {
        // 달을 고정하는 것과 처음부터 빈 달을 여는 것은 다르다
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 8), viewModel.uiState.value.currentMonth)
    }

    @Test
    fun `필터를 바꾸면 서버 조건도 바뀐다`() = runTest(dispatcher) {
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.onFilterApply(RaceFilter(events = setOf("풀"), openOnly = true))
        advanceUntilIdle()

        assertEquals(listOf(EventType.FULL), repository.lastFilter?.events)
        assertEquals(true, repository.lastFilter?.openOnly)
    }

    @Test
    fun `받을 장이 남았으면 목록이 비어도 Empty 로 확정하지 않는다`() = runTest(dispatcher) {
        // 9월 대회가 다음 장에 있는데 9월을 열면 지금까지 받은 것 중에는 하나도 없다.
        // 그걸 Empty 로 굳히면 달력엔 점이 있는데 아래는 "없어요" 가 된다 (#85 리뷰)
        repository.hasSecondPage = true
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.onViewModeChange(CalendarViewMode.CALENDAR)
        viewModel.onMonthChange(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("이 달 목록이 비어 있어야 하는 전제가 깨졌다", state.listedRaces.isEmpty())
        assertFalse("아직 받을 장이 남았는데 Empty 로 확정했다", state.showsEmpty)
        assertTrue("더 받기 자리가 없어 다음 장을 받을 방법이 없다", state.showsLoadMore)
    }

    @Test
    fun `다 받았고 목록이 비면 그때 Empty 다`() = runTest(dispatcher) {
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.onViewModeChange(CalendarViewMode.CALENDAR)
        viewModel.onMonthChange(6)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.listedRaces.isEmpty())
        assertTrue(state.showsEmpty)
    }

    @Test
    fun `다음 장이 실패하면 자동 재시도하지 않고 안내를 남긴다`() = runTest(dispatcher) {
        // 자동 재시도로 두면 네트워크가 끊긴 동안 같은 요청을 계속 던진다
        repository.hasSecondPage = true
        repository.failNextPage = true
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("로딩 표시가 남았다", state.loadingMore)
        assertTrue("실패 안내가 없다", state.loadMoreError != null)
        assertTrue("다시 받을 수 있어야 한다", state.showsLoadMore)
    }

    @Test
    fun `다시 시도하면 실패 안내를 지우고 이어 받는다`() = runTest(dispatcher) {
        repository.hasSecondPage = true
        repository.failNextPage = true
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()
        viewModel.loadMore()
        advanceUntilIdle()

        repository.failNextPage = false
        viewModel.loadMore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.loadMoreError)
        assertEquals(2, state.allRaces.size)
    }

    @Test
    fun `다음 장은 목록에 이어 붙고 중복은 한 번만 남는다`() = runTest(dispatcher) {
        repository.hasSecondPage = true
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()
        val first = viewModel.uiState.value.allRaces.size

        viewModel.loadMore()
        advanceUntilIdle()

        val ids = viewModel.uiState.value.allRaces.map { it.id }
        assertEquals("중복이 남았다", ids.size, ids.toSet().size)
        assertEquals(first + 1, ids.size)
    }
}

/** 넘겨받은 조건을 기록하는 가짜 저장소. */
private class RecordingContestRepository : ContestRepository {

    var lastFilter: ContestFilter? = null
        private set
    var listCalls = 0
        private set

    /** true 면 첫 장에 다음 커서를 붙인다. */
    var hasSecondPage = false

    /** true 면 다음 장 조회만 실패시킨다. */
    var failNextPage = false

    override suspend fun list(filter: ContestFilter, cursor: String?): ContestPage {
        listCalls++
        lastFilter = filter
        return if (cursor == null) {
            ContestPage(
                contests = listOf(contest("1", LocalDate.of(2026, 8, 22))),
                nextCursor = if (hasSecondPage) "cursor-2" else null,
                hasNext = hasSecondPage,
            )
        } else {
            if (failNextPage) throw ApiException.Network(java.io.IOException("끊김"))
            // 첫 장의 1 번이 다시 온다 — 조회 중 원천이 갱신되면 실제로 생기는 일이다
            ContestPage(
                contests = listOf(
                    contest("1", LocalDate.of(2026, 8, 22)),
                    contest("2", LocalDate.of(2026, 9, 5)),
                ),
                nextCursor = null,
                hasNext = false,
            )
        }
    }

    override suspend fun dailyCounts(
        year: Int,
        month: Int,
        filter: ContestFilter,
    ): Map<LocalDate, Int> = mapOf(LocalDate.of(year, month, 10) to 1)

    override suspend fun closingSoon(limit: Int): List<ClosingSoon> = emptyList()

    override suspend fun detail(id: Long): Contest = contest(id.toString(), LocalDate.of(2026, 8, 22))

    /** 캘린더는 인근 축제를 쓰지 않는다. S3 상세 전용이다(§3-5). */
    override suspend fun festivals(id: Long): List<NearbyFestival> = emptyList()

    private fun contest(id: String, date: LocalDate) = Contest(
        id = id,
        serverId = id.toLongOrNull(),
        name = "대회 $id",
        region = "세종",
        venue = "세종중앙공원",
        date = date,
        startTime = null,
        eventTypes = listOf(EventType.FULL),
        regStart = null,
        regEnd = null,
        regStatusFallback = RegistrationStatus.OPEN,
        organizer = null,
        officialUrl = null,
        detailUrl = null,
        imageUrl = null,
        lat = null,
        lng = null,
        category = null,
        checked = null,
        active = true,
        sources = listOf("MARATHON_GO"),
    )
}
